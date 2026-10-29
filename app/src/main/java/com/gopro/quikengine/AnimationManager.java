package com.gopro.quikengine;

/** Only the nested State type is needed; the engine resolves it at load time. */
public final class AnimationManager {
    public enum State {
        USER_REFRAMING,
        GESTURES_REFRAMING,
        TRANSITION
    }

    private AnimationManager() {
    }
}
