package com.gopro.quikengine.model;

public final class PythonError extends EngineError {
    private final String message;
    private final String function;
    private final String script;
    private final String arguments;
    private final String stacktrace;

    public PythonError(String message, String function, String script, String arguments, String stacktrace) {
        this.message = message;
        this.function = function;
        this.script = script;
        this.arguments = arguments;
        this.stacktrace = stacktrace;
    }

    @Override
    public String getMessage() {
        return message;
    }

    public final String getFunction() {
        return function;
    }

    public final String getScript() {
        return script;
    }

    public final String getArguments() {
        return arguments;
    }

    @Override
    public String getStacktrace() {
        return stacktrace;
    }

    @Override
    public String toString() {
        return "PythonError(message=" + message + ", function=" + function + ", script=" + script + ", arguments=" + arguments + ", stacktrace=" + stacktrace + ")";
    }
}
