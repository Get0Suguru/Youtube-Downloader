package com.suguru.geto.yt_downloader.service;

import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.suguru.geto.yt_downloader.model.FormatInfo;

@Service
public class YoutubeDownloadService {

    private Path ensureDownloadDir() throws Exception {
        Path downloadDir = Paths.get(System.getProperty("user.home"), "Downloads", "ytd").toAbsolutePath();
        Files.createDirectories(downloadDir);
        return downloadDir;
    }

    // LEGACY: kept for compatibility if needed elsewhere
    public String downloadVideo(String videoUrl, String quality) {
        try {
            Path downloadDir = ensureDownloadDir();
            String formatSelector = mapQualityToFormat(quality);
            String outputTemplate = downloadDir.resolve("%(title)s.%(ext)s").toString().replace('\\', '/');
            ProcessBuilder pb = new ProcessBuilder(
                    "yt-dlp",
                    "-f", formatSelector,
                    "-o", outputTemplate,
                    videoUrl
            );
            return runProcessAndSummarize(pb);
        } catch (Exception e) {
            return "Exception: " + e.getMessage();
        }
    }

    public List<FormatInfo> listFormats(String videoUrl) {
        List<FormatInfo> formats = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder("yt-dlp", "--list-formats", videoUrl);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            boolean tableStarted = false;
            boolean headerSkipped = false;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String l = line.trim();
                    if (l.isEmpty()) continue;

                    // Skip until we find the header line
                    if (!tableStarted) {
                        if (l.contains("ID") && l.contains("EXT") && l.contains("RESOLUTION")) {
                            tableStarted = true;
                            continue;
                        }
                        continue;
                    }

                    // Skip separator line after header
                    if (!headerSkipped) {
                        headerSkipped = true;
                        if (l.matches("^[─│┼╭╮╰╯\\-|]+$")) {
                            continue;
                        }
                    }

                    // Skip storyboard entries
                    if (l.startsWith("sb")) continue;

                    // Split by pipe and clean up
                    String[] parts = l.split("\\s*\\|\\s*");
                    if (parts.length < 3) continue;

                    try {
                        // First part: ID EXT RESOLUTION FPS
                        String[] firstPart = parts[0].trim().split("\\s+");
                        if (firstPart.length < 2) continue;
                        
                        String id = firstPart[0].trim();
                        String ext = firstPart[1].trim();
                        
                        String resolution = "";
                        String fps = "";
                        
                        // Look for resolution and fps in the remaining parts
                        for (int i = 2; i < firstPart.length; i++) {
                            String part = firstPart[i].trim();
                            if (part.matches("\\d+x\\d+")) {
                                resolution = part;
                            } else if (part.matches("\\d+")) {
                                fps = part;
                            } else if (part.equals("audio") && i + 1 < firstPart.length && firstPart[i + 1].equals("only")) {
                                resolution = "audio only";
                                break;
                            }
                        }

                        // Second part: FILESIZE TBR PROTO
                        String[] secondPart = parts[1].trim().split("\\s+");
                        String fileSize = "";
                        String tbr = "";
                        
                        for (String part : secondPart) {
                            part = part.trim();
                            if (part.matches(".*[KMGT]iB") || part.startsWith("~")) {
                                fileSize = part;
                            } else if (part.matches("\\d+k?")) {
                                tbr = part;
                            }
                        }

                        // Third part: VCODEC VBR ACODEC MORE INFO
                        String[] thirdPart = parts[2].trim().split("\\s+");
                        String vcodec = "";
                        String vbr = "";
                        String acodec = "";
                        String type = "video"; // Default to video
                        
                        StringBuilder noteBuilder = new StringBuilder();
                        boolean isAudioOnly = false;
                        boolean isVideoOnly = false;
                        
                        for (int i = 0; i < thirdPart.length; i++) {
                            String part = thirdPart[i].trim();
                            if (part.equals("audio") && i + 1 < thirdPart.length && thirdPart[i + 1].equals("only")) {
                                isAudioOnly = true;
                                noteBuilder.append("audio only ");
                                i++; // skip "only"
                            } else if (part.equals("video") && i + 1 < thirdPart.length && thirdPart[i + 1].equals("only")) {
                                isVideoOnly = true;
                                noteBuilder.append("video only ");
                                i++; // skip "only"
                            } else if (i == 0 && !isAudioOnly) {
                                vcodec = part;
                            } else if (i == 1 && part.matches("\\d+k?") && !isAudioOnly) {
                                vbr = part;
                            } else {
                                noteBuilder.append(part).append(" ");
                            }
                        }
                        
                        String note = noteBuilder.toString().trim();

                        // Determine format type
                        if (resolution.equals("audio only") || isAudioOnly) {
                            type = "audio";
                            resolution = ""; // Clear resolution for audio formats
                            acodec = "mp3"; // Default audio codec
                        } else if (isVideoOnly || resolution.matches("\\d+x\\d+")) {
                            type = "video";
                        }

                        // Parse file size
                        Long filesize = null;
                        if (fileSize.startsWith("~")) fileSize = fileSize.substring(1).trim();
                        if (!fileSize.isEmpty()) {
                            filesize = humanToBytes(fileSize);
                        }

                        // Create and add format info
                        FormatInfo info = new FormatInfo(
                            id, ext, resolution, fps, vcodec, acodec, 
                            tbr, note, type, filesize
                        );
                        formats.add(info);
                        
                    } catch (Exception e) {
                        // Skip malformed lines silently
                    }
                }
            }

            int exit = p.waitFor();
            if (exit != 0 && formats.isEmpty()) {
                throw new RuntimeException("yt-dlp --list-formats failed with exit code " + exit);
            }
            
            return formats;
        } catch (Exception e) {
            throw new RuntimeException("Failed to list formats: " + e.getMessage(), e);
        }
    }

    public String downloadVideoWithBestAudio(String videoUrl, String videoFormatId) {
        try {
            Path downloadDir = ensureDownloadDir();
            String outputTemplate = downloadDir.resolve("%(title)s.%(ext)s").toString().replace('\\', '/');
            
            // Detect if selected video format is progressive (already has audio)
            List<FormatInfo> fmts = listFormats(videoUrl);
            FormatInfo chosen = null;
            for (FormatInfo f : fmts) {
                if (videoFormatId.equals(f.getId())) { chosen = f; break; }
            }

            String formatArg;
            boolean progressive = false;
            if (chosen != null) {
                if ("video".equalsIgnoreCase(chosen.getType()) && chosen.getAcodec() != null && !chosen.getAcodec().isEmpty()) {
                    progressive = true;
                }
                if (chosen.getNote() != null && chosen.getNote().toLowerCase().contains("video only")) {
                    progressive = false;
                }
            }

            if (progressive) {
                formatArg = videoFormatId;
                ProcessBuilder pb = new ProcessBuilder("yt-dlp", "-f", formatArg, "-o", outputTemplate, videoUrl);
                return runProcessAndSummarize(pb);
            } else {
                String bestAudioId = findBestAudioId(fmts);
                if (bestAudioId == null) {
                    return "Could not determine best audio format id";
                }
                formatArg = videoFormatId + "+" + bestAudioId;
                ProcessBuilder pb = new ProcessBuilder("yt-dlp", "-f", formatArg, "-o", outputTemplate, videoUrl);
                return runProcessAndSummarize(pb);
            }
        } catch (Exception e) {
            return "Exception: " + e.getMessage();
        }
    }

    public String downloadAudioMp3(String videoUrl, String audioFormatId) {
        try {
            Path downloadDir = ensureDownloadDir();
            String outputTemplate = downloadDir.resolve("%(title)s.%(ext)s").toString().replace('\\', '/');
            
            ProcessBuilder pb = new ProcessBuilder(
                "yt-dlp",
                "-x",
                "--audio-format", "mp3",
                "-f", audioFormatId,
                "-o", outputTemplate,
                videoUrl
            );
            return runProcessAndSummarize(pb);
        } catch (Exception e) {
            return "Exception: " + e.getMessage();
        }
    }

    private String findBestAudioId(List<FormatInfo> formats) {
        List<FormatInfo> audios = formats.stream()
                .filter(f -> "audio".equalsIgnoreCase(f.getType()))
                .collect(Collectors.toList());
        if (audios.isEmpty()) return null;

        // Rank by note quality first: high > medium > low > unknown
        audios.sort((a, b) -> {
            int qa = audioQualityRank(a.getNote());
            int qb = audioQualityRank(b.getNote());
            if (qa != qb) return Integer.compare(qb, qa);
            // Then by abr desc
            int abrA = parseIntSafe(a.getAbr());
            int abrB = parseIntSafe(b.getAbr());
            if (abrA != abrB) return Integer.compare(abrB, abrA);
            // Then by codec preference (opus > aac > others)
            int prefA = audioCodecPreference(a.getAcodec());
            int prefB = audioCodecPreference(b.getAcodec());
            if (prefA != prefB) return Integer.compare(prefB, prefA);
            // Finally, stable by id desc (often higher itag ~ higher quality within family)
            return safeInt(b.getId()) - safeInt(a.getId());
        });

        return audios.get(0).getId();
    }

    private int audioQualityRank(String note) {
        if (note == null) return -1;
        String n = note.toLowerCase();
        if (n.contains("high")) return 3;
        if (n.contains("medium")) return 2;
        if (n.contains("low")) return 1;
        return 0;
    }

    private int audioCodecPreference(String acodec) {
        if (acodec == null) return 0;
        String a = acodec.toLowerCase();
        if (a.contains("opus") || a.contains("webm")) return 3;
        if (a.contains("aac") || a.contains("m4a")) return 2;
        if (a.contains("mp3")) return 1;
        return 0;
    }

    private int safeInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }

    private int parseIntSafe(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }

    private String runProcessAndSummarize(ProcessBuilder pb) throws Exception {
        pb.redirectErrorStream(true);
        Process process = pb.start();
        StringBuilder output = new StringBuilder(2048);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Silently consume all output
                if (output.length() < 2000) {
                    output.append(line).append('\n');
                }
            }
        }
        int exitCode = process.waitFor();
        if (exitCode == 0) {
            return "Download completed successfully!";
        } else {
            return "Download failed (" + exitCode + ")";
        }
    }

    private String mapQualityToFormat(String quality) {
        String q = quality == null ? "best" : quality.trim().toLowerCase();
        switch (q) {
            case "1080p":
            case "1080":
                return "bestvideo[height<=1080][vcodec!=av01]+bestaudio/best[height<=1080]";
            case "720p":
            case "720":
                return "bestvideo[height<=720][vcodec!=av01]+bestaudio/best[height<=720]";
            case "480p":
            case "480":
                return "bestvideo[height<=480][vcodec!=av01]+bestaudio/best[height<=480]";
            case "360p":
            case "360":
                return "bestvideo[height<=360][vcodec!=av01]+bestaudio/best[height<=360]";
            case "best":
            default:
                return "best";
        }
    }

    private Long humanToBytes(String size) {
        if (size == null || size.isEmpty()) return null;
        try {
            size = size.toUpperCase();
            if (size.endsWith("KIB")) {
                return (long) (Double.parseDouble(size.replace("KIB", "").trim()) * 1024);
            } else if (size.endsWith("MIB")) {
                return (long) (Double.parseDouble(size.replace("MIB", "").trim()) * 1024 * 1024);
            } else if (size.endsWith("GIB")) {
                return (long) (Double.parseDouble(size.replace("GIB", "").trim()) * 1024 * 1024 * 1024);
            } else if (size.endsWith("KB")) {
                return (long) (Double.parseDouble(size.replace("KB", "").trim()) * 1000);
            } else if (size.endsWith("MB")) {
                return (long) (Double.parseDouble(size.replace("MB", "").trim()) * 1000 * 1000);
            } else if (size.endsWith("GB")) {
                return (long) (Double.parseDouble(size.replace("GB", "").trim()) * 1000 * 1000 * 1000);
            } else if (size.endsWith("B")) {
                return Long.parseLong(size.replace("B", "").trim());
            } else {
                // Assume bytes if no unit specified
                return Long.parseLong(size.trim());
            }
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Fast text parse of `yt-dlp -F <url>` to obtain human sizes per itag (when available).
     * Returns a map: format_id -> bytes. Unknown sizes are omitted.
     */
    public Map<String, Long> getFormatSizes(String videoUrl) {
        Map<String, Long> out = new HashMap<>();
        try {
            ProcessBuilder pb = new ProcessBuilder("yt-dlp", "-F", videoUrl);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                boolean tableStarted = false;
                while ((line = reader.readLine()) != null) {
                    // Detect header or separator: either starts with "ID" (ID EXT RESOLUTION ...)
                    // or contains a long sequence of box-drawing/dash characters.
                    if (!tableStarted) {
                        String lu = line.toUpperCase();
                        if (lu.startsWith("ID ") || lu.startsWith("ID\t") || line.matches("[\\u2500-\\u257F\\-\\s]{6,}")) {
                            tableStarted = true;
                            continue;
                        }
                        // Also, if a line already appears to start with a numeric itag, treat it as data.
                        int sp0 = line.indexOf(' ');
                        if (sp0 > 0) {
                            String idMaybe = line.substring(0, sp0).trim();
                            if (!idMaybe.isEmpty() && idMaybe.chars().allMatch(Character::isDigit)) {
                                tableStarted = true; // fall through to parse below
                            } else {
                                continue;
                            }
                        } else {
                            continue;
                        }
                    }

                    // Expect lines starting with a format id (we only keep numeric itags; skip storyboard like sb0)
                    int sp = line.indexOf(' ');
                    if (sp <= 0) continue;
                    String idCandidate = line.substring(0, sp).trim();
                    if (!idCandidate.chars().allMatch(Character::isDigit)) continue;

                    // Try to find a size token like "123.4MiB", "950KiB", or "1.2GiB"
                    Long bytes = extractSizeBytes(line);
                    if (bytes != null && bytes > 0) {
                        out.put(idCandidate, bytes);
                    }
                }
            }
            p.waitFor();
        } catch (Exception e) {
            // Ignore errors to keep responsiveness; simply return what we have.
        }
        return out;
    }

    private Long extractSizeBytes(String line) {
        // Scan tokens for pattern <number><unit> where unit in {KiB,MiB,GiB}
        String[] toks = line.split("\\s+");
        for (String t : toks) {
            Long v = humanToBytes(t);
            if (v != null) return v;
        }
        return null;
    }
}