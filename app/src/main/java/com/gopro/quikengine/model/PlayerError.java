package com.gopro.quikengine.model;

public final class PlayerError extends EngineError {
    private final String message;
    private final String stacktrace;

    public PlayerError(String message, String stacktrace) {
        this.message = message;
        this.stacktrace = stacktrace;
    }

    @Override
    public String getMessage() {
        return message;
    }

    @Override
    public String getStacktrace() {
        return stacktrace;
    }

    @Override
    public String toString() {
        return "PlayerError(message=" + message + ", stacktrace=" + stacktrace + ")";
    }
}
