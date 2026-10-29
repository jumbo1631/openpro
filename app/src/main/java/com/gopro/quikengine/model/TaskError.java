package com.gopro.quikengine.model;

public final class TaskError extends EngineError {
    private final String message;
    private final String exception;
    private final String stacktrace;

    public TaskError(String message, String exception, String stacktrace) {
        this.message = message;
        this.exception = exception;
        this.stacktrace = stacktrace;
    }

    @Override
    public String getMessage() {
        return message;
    }

    public final String getException() {
        return exception;
    }

    @Override
    public String getStacktrace() {
        return stacktrace;
    }

    @Override
    public String toString() {
        return "TaskError(message=" + message + ", exception=" + exception + ", stacktrace=" + stacktrace + ")";
    }
}
