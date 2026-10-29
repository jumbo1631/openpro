package com.gopro.quikengine.services;

public final class LogDispatcher {
    public enum Level {
        Verbose,
        Debug,
        Info,
        Warning,
        Error,
        Exception,
        Assert,
        None
    }

    public interface Listener {
        void onLog(int threadId, Level level, String tag, int line, String message);
    }

    private LogDispatcher() {
    }

    public static void addFilter(String filter) {
        addFilterInternal(filter);
    }

    public static void removeFilter(String filter) {
        removeFilterInternal(filter);
    }

    public static void setLevel(Level level) {
        setLevelInternal(level.ordinal());
    }

    public static void setListener(Listener listener) {
        setListenerInternal(listener);
    }

    private static native void addFilterInternal(String filter);

    private static native void removeFilterInternal(String filter);

    private static native void setLevelInternal(int level);

    private static native void setListenerInternal(Listener listener);
}
