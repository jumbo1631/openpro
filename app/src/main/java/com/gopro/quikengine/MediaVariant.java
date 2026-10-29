package com.gopro.quikengine;

public enum MediaVariant {
    DEFAULT(0),
    SCE_LOW(1),
    SCE_OPTIMIZED(2),
    SCE_FULL(3),
    MIN(4),
    MAX(5),
    FAST(6);

    private final int value;

    MediaVariant(int value) {
        this.value = value;
    }

    public final int getValue() {
        return value;
    }
}
