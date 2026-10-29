package com.gopro.quikengine.model;

public enum VideoCodec {
    H264("video/avc"),
    HEVC("video/hevc");

    private final String mimeType;

    VideoCodec(String mimeType) {
        this.mimeType = mimeType;
    }

    public final String getMimeType() {
        return mimeType;
    }
}
