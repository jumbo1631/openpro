package com.gopro.quikengine.services;

/** Runs named native tasks ("MediaInfo", "SourceInfo", ...). Natives are bound on the singleton. */
public final class TaskManager {
    public static final TaskManager INSTANCE = new TaskManager();

    private TaskManager() {
    }

    private native void addArgumentBooleanInternal(long handle, boolean value);

    private native void addArgumentDoubleInternal(long handle, double value);

    private native void addArgumentFloatInternal(long handle, float value);

    private native void addArgumentIntegerInternal(long handle, int value);

    private native void addArgumentStringInternal(long handle, String value);

    private native long allocateArgumentsHandleInternal();

    private native void clearCacheInternal();

    private native void deallocateArgumentsHandleInternal(long handle);

    private native void executeAsyncInternal(Task handle, String name, long argumentPack);

    private native void executeInternal(long handle, String name, long argumentPack);

    private native void setWorkersNumberInternal(int workers);

    private long createArguments(Object... arguments) {
        long handle = allocateArgumentsHandleInternal();
        for (Object arg : arguments) {
            if (arg == null) {
                continue;
            }
            if (arg instanceof Integer) {
                addArgumentIntegerInternal(handle, (Integer) arg);
            } else if (arg instanceof Boolean) {
                addArgumentBooleanInternal(handle, (Boolean) arg);
            } else if (arg instanceof Float) {
                addArgumentFloatInternal(handle, (Float) arg);
            } else if (arg instanceof Double) {
                addArgumentDoubleInternal(handle, (Double) arg);
            } else if (arg instanceof String) {
                addArgumentStringInternal(handle, (String) arg);
            } else {
                destroyArguments(handle);
                throw new IllegalArgumentException("[TaskManager] Argument type " + arg.getClass().getSimpleName() + " not supported");
            }
        }
        return handle;
    }

    private void destroyArguments(long handle) {
        if (handle != 0) {
            deallocateArgumentsHandleInternal(handle);
        }
    }

    public void clearCache() {
        clearCacheInternal();
    }

    public Task execute(String taskName, Object... arguments) {
        Task task = new Task(null);
        long args = createArguments(arguments);
        executeInternal(task.getNativeRef(), taskName, args);
        destroyArguments(args);
        return task;
    }

    public Task executeAsync(String taskName, Task.Callback callback, Object... arguments) {
        Task task = new Task(callback);
        long args = createArguments(arguments);
        executeAsyncInternal(task, taskName, args);
        destroyArguments(args);
        return task;
    }

    public void setWorkersNumber(int workers) {
        if (workers < 1 || workers > 4) {
            throw new IllegalArgumentException("acceptable range is [1, 4]");
        }
        setWorkersNumberInternal(workers);
    }
}
