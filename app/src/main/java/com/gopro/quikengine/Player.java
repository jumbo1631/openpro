package com.gopro.quikengine;

/**
 * GoPro's realtime preview player. OpenPro renders previews through Exporter instead, but the
 * engine resolves these nested types at load time, so they must exist.
 */
public final class Player {
    public enum State {
        PLAYING,
        PAUSED,
        STOPPED
    }

    public enum ReframingEndReason {
        COMPLETED,
        INTERRUPTED,
        MAX_END_TIME_REACHED,
        MEDIA_END_TIME_REACHED,
        TRACKING_LOST,
        INTERNAL_ERROR,
        TRACKING_MEDIA_DECODING_ERROR,
        TRACKING_OUT_OF_MEMORY_ERROR
    }

    private Player() {
    }
}
