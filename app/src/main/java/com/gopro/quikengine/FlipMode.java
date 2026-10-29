package com.gopro.quikengine;

public enum FlipMode {
    NONE(0),
    HORIZONTAL(1),
    VERTICAL(2);

    private final int value;

    FlipMode(int value) {
        this.value = value;
    }

    public final int getValue() {
        return value;
    }
}
