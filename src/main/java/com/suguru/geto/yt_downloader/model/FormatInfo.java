package com.suguru.geto.yt_downloader.model;

public class FormatInfo {
    private String id;
    private String ext;
    private String resolution; // e.g., 1920x1080 or audio only
    private String fps;
    private String vcodec;
    private String acodec;
    private String abr; // audio bitrate if present
    private String note; // raw description/size info
    private String type; // "video" or "audio"
    private Long filesize; // bytes (from filesize or filesize_approx)

    public FormatInfo() {}

    public FormatInfo(String id, String ext, String resolution, String fps, String vcodec, String acodec, String abr, String note, String type) {
        this(id, ext, resolution, fps, vcodec, acodec, abr, note, type, null);
    }

    public FormatInfo(String id, String ext, String resolution, String fps, String vcodec, String acodec, String abr, String note, String type, Long filesize) {
        this.id = id;
        this.ext = ext;
        this.resolution = resolution;
        this.fps = fps;
        this.vcodec = vcodec;
        this.acodec = acodec;
        this.abr = abr;
        this.note = note;
        this.type = type;
        this.filesize = filesize;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getExt() { return ext; }
    public void setExt(String ext) { this.ext = ext; }
    public String getResolution() { return resolution; }
    public void setResolution(String resolution) { this.resolution = resolution; }
    public String getFps() { return fps; }
    public void setFps(String fps) { this.fps = fps; }
    public String getVcodec() { return vcodec; }
    public void setVcodec(String vcodec) { this.vcodec = vcodec; }
    public String getAcodec() { return acodec; }
    public void setAcodec(String acodec) { this.acodec = acodec; }
    public String getAbr() { return abr; }
    public void setAbr(String abr) { this.abr = abr; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Long getFilesize() { return filesize; }
    public void setFilesize(Long filesize) { this.filesize = filesize; }
}
