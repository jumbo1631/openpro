package com.gopro.quikengine.model;

public final class EditProxyProcessingError extends AssetStoreError {
    private final String message;
    private final String mediumId;
    private final String stacktrace;

    public EditProxyProcessingError(String message, String mediumId, String stacktrace) {
        this.message = message;
        this.mediumId = mediumId;
        this.stacktrace = stacktrace;
    }

    @Override
    public String getMessage() {
        return message;
    }

    public final String getMediumId() {
        return mediumId;
    }

    @Override
    public String getStacktrace() {
        return stacktrace;
    }

    @Override
    public String toString() {
        return "EditProxyProcessingError(message=" + message + ", mediumId=" + mediumId + ", stacktrace=" + stacktrace + ")";
    }
}
