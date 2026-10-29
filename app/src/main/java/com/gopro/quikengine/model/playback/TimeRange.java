package com.gopro.quikengine.model.playback;

public final class TimeRange {
    private final double start;
    private final double end;

    public TimeRange(double start, double end) {
        this.start = start;
        this.end = end;
    }

    public final double getStart() {
        return start;
    }

    public final double getEnd() {
        return end;
    }

    @Override
    public String toString() {
        return "TimeRange(start=" + start + ", end=" + end + ")";
    }
}
