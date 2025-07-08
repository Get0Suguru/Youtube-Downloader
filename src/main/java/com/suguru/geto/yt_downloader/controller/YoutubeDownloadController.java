package com.suguru.geto.yt_downloader.controller;

import com.suguru.geto.yt_downloader.model.FormatInfo;
import com.suguru.geto.yt_downloader.service.YoutubeDownloadService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/youtube")
public class YoutubeDownloadController {

    private final YoutubeDownloadService youtubeDownloadService;

    public YoutubeDownloadController(YoutubeDownloadService youtubeDownloadService) {
        this.youtubeDownloadService = youtubeDownloadService;
    }

    /**
     * Legacy endpoint (kept for compatibility)
     * Example: /api/youtube/download?url=https://youtube.com/xyz&quality=720p
     */
    @GetMapping("/download")
    public ResponseEntity<String> downloadVideo(@RequestParam("url") String url,
                                                @RequestParam(value = "quality", required = false, defaultValue = "best") String quality) {
        if (url == null || url.isEmpty()) {
            return ResponseEntity.badRequest().body("YouTube video URL must be provided");
        }
        String result = youtubeDownloadService.downloadVideo(url, quality);
        return ResponseEntity.ok(result);
    }

    /**
     * List available formats using yt-dlp JSON (-J)
     */
    @GetMapping("/formats")
    public ResponseEntity<?> listFormats(@RequestParam("url") String url) {
        if (url == null || url.isEmpty()) {
            return ResponseEntity.badRequest().body("YouTube video URL must be provided");
        }
        try {
            List<FormatInfo> formats = youtubeDownloadService.listFormats(url);
            return ResponseEntity.ok(formats);
        } catch (Exception e) {
            System.err.println("[ERROR] Failed to get formats: " + e.getMessage());
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }

    /**
     * Download a specific video format id combined with best audio automatically.
     * CLI: yt-dlp -f vid_id+aud_id <url>
     */
    @GetMapping("/download/video")
    public ResponseEntity<String> downloadVideoWithBestAudio(@RequestParam("url") String url,
                                                             @RequestParam("videoFormatId") String videoFormatId) {
        if (url == null || url.isEmpty() || videoFormatId == null || videoFormatId.isEmpty()) {
            return ResponseEntity.badRequest().body("Both url and videoFormatId are required");
        }
        String result = youtubeDownloadService.downloadVideoWithBestAudio(url, videoFormatId);
        return ResponseEntity.ok(result);
    }

    /**
     * Download audio-only as mp3 using extract-audio approach.
     * CLI: yt-dlp -f <aud_id> --extract-audio --audio-format mp3 <url>
     */
    @GetMapping("/download/audio")
    public ResponseEntity<String> downloadAudio(@RequestParam("url") String url,
                                                @RequestParam("audioFormatId") String audioFormatId) {
        if (url == null || url.isEmpty() || audioFormatId == null || audioFormatId.isEmpty()) {
            return ResponseEntity.badRequest().body("Both url and audioFormatId are required");
        }
        String result = youtubeDownloadService.downloadAudioMp3(url, audioFormatId);
        return ResponseEntity.ok(result);
    }
}