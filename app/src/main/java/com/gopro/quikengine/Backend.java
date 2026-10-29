package com.gopro.quikengine;

public final class Backend {
    public enum Type {
        AUTO,
        OPENGL,
        OPENGLES,
        VULKAN
    }

    private Backend() {
    }

    public static final native Type[] getAvailableBackends();
}
