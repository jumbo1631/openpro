package com.gopro.quikengine.services;

import com.gopro.quikengine.model.TaskError;
import com.gopro.quikengine.utils.JniBinding;

/** A native background task (MediaInfo, SourceInfo, ...) started through TaskManager. */
public final class Task extends JniBinding {
    private final Callback callback;

    public interface Callback {
        void onChanged(Task task);
    }

    public enum State {
        Created,
        Pending,
        Executing,
        Finished,
        Canceled,
        Error
    }

    public Task(Callback callback) {
        super(allocateHandleInternal(), Task::deallocateHandleInternal, null);
        this.callback = callback;
    }

    public Task() {
        this(null);
    }

    public static final native long allocateHandleInternal();

    public static final native void cancelInternal(long handle);

    public static final native void deallocateHandleInternal(long handle);

    public static final native String getErrorExceptionInternal(long handle);

    public static final native String getErrorMessageInternal(long handle);

    public static final native String getErrorStacktraceInternal(long handle);

    public static final native String getNameInternal(long handle);

    public static final native String getResultInternal(long handle);

    public static final native int getStateInternal(long handle);

    public static final native int getVersionInternal(long handle);

    public static final native float getprogressInternal(long handle);

    public static final native boolean hasErrorInternal(long handle);

    public static final native boolean isFinishedInternal(long handle);

    public static final native void waitInternal(long handle);

    public final void cancel() {
        cancelInternal(getNativeRef());
    }

    /** Kotlin-internal getter name in GoPro's build; the engine may look it up by this name. */
    public final Callback getCallback$QuikEngine_release() {
        return callback;
    }

    public final TaskError getError() {
        if (!hasError()) {
            return null;
        }
        long ref = getNativeRef();
        return new TaskError(getErrorMessageInternal(ref), getErrorExceptionInternal(ref), getErrorStacktraceInternal(ref));
    }

    public final String getName() {
        return getNameInternal(getNativeRef());
    }

    public final float getProgress() {
        return getprogressInternal(getNativeRef());
    }

    public final String getResult() {
        return getResultInternal(getNativeRef());
    }

    public final State getState() {
        return State.values()[getStateInternal(getNativeRef())];
    }

    public final int getVersion() {
        return getVersionInternal(getNativeRef());
    }

    public final boolean hasError() {
        return hasErrorInternal(getNativeRef());
    }

    public final boolean isFinished() {
        return isFinishedInternal(getNativeRef());
    }

    public final void waitFinish() {
        waitInternal(getNativeRef());
    }
}
