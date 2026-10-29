package com.gopro.quikengine.model;

public abstract class EngineError {
    EngineError() {
    }

    public abstract String getMessage();

    public abstract String getStacktrace();
}
