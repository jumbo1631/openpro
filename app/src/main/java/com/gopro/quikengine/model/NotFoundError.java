package com.gopro.quikengine.model;

public final class NotFoundError extends AssetStoreError {
    private final String message;
    private final String stacktrace;

    public NotFoundError() {
        this("", "");
    }

    public NotFoundError(String message, String stacktrace) {
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
        return "NotFoundError(message=" + message + ", stacktrace=" + stacktrace + ")";
    }
}
