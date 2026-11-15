package com.tianmu.agent.core;

/** Page selection is an input hint, never an authorization grant. IDs stay strings on the wire. */
public record RequestContext(String videoId, String videoTitle, Long playbackMs, String sourceVersion) {
    public RequestContext(String videoId) { this(videoId, "", null, null); }
    public RequestContext(String videoId,String videoTitle,Long playbackMs){this(videoId,videoTitle,playbackMs,null);}
    public String forParsing(String message) { return DanmakuTarget.matcher(message).find() ? message : message+"\n视频"+videoId; }
    public RequestContext {
        if (videoId == null || !videoId.matches("[1-9][0-9]{0,18}"))
            throw new AgentException("INVALID_REQUEST", "Invalid context videoId");
        try { Long.parseLong(videoId); }
        catch (NumberFormatException e) { throw new AgentException("INVALID_REQUEST", "Invalid context videoId"); }
        if(sourceVersion!=null && !sourceVersion.matches("[a-f0-9]{64}")) throw new AgentException("INVALID_REQUEST", "Invalid player source version");
        videoTitle = videoTitle == null ? "" : videoTitle;
        if (videoTitle.length() > 160 || playbackMs != null && (playbackMs < 0 || playbackMs > 3_600_000))
            throw new AgentException("INVALID_REQUEST", "Invalid page context");
    }
}
