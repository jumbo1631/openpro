package com.gopro.quikengine.model;

public final class PerformanceError {
    private final String message;
    private final String arguments;
    private final String report;
    private final String stacktrace;

    public PerformanceError(String message, String arguments, String report, String stacktrace) {
        this.message = message;
        this.arguments = arguments;
        this.report = report;
        this.stacktrace = stacktrace;
    }

    public final String getMessage() {
        return message;
    }

    public final String getArguments() {
        return arguments;
    }

    public final String getReport() {
        return report;
    }

    public final String getStacktrace() {
        return stacktrace;
    }

    @Override
    public String toString() {
        return "PerformanceError(message=" + message + ", arguments=" + arguments + ", report=" + report + ", stacktrace=" + stacktrace + ")";
    }
}
