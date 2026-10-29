package com.gopro.quikengine.model;

public final class ExportError extends EngineError {
    private final String message;
    private final String arguments;
    private final String stacktrace;
    private final ExportErrorType errorType;

    public ExportError(String message, String arguments, String stacktrace, ExportErrorType errorType) {
        this.message = message;
        this.arguments = arguments;
        this.stacktrace = stacktrace;
        this.errorType = errorType;
    }

    @Override
    public String getMessage() {
        return message;
    }

    public final String getArguments() {
        return arguments;
    }

    @Override
    public String getStacktrace() {
        return stacktrace;
    }

    public final ExportErrorType getErrorType() {
        return errorType;
    }

    @Override
    public String toString() {
        return "ExportError(message=" + message + ", arguments=" + arguments + ", stacktrace=" + stacktrace + ", errorType=" + errorType + ")";
    }
}
