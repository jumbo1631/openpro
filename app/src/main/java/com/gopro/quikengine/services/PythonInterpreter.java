package com.gopro.quikengine.services;

import com.gopro.quikengine.model.PythonError;
import com.gopro.quikengine.utils.JniBinding;

import java.util.UUID;

/** Calls functions in the engine's embedded Python ("director" etc). Natives bind on INSTANCE. */
public final class PythonInterpreter {
    public static final PythonInterpreter INSTANCE = new PythonInterpreter();

    public static final class ArgumentsCreationException extends Exception {
        public ArgumentsCreationException(String message) {
            super(message);
        }
    }

    public interface Listener {
        void onPythonFailed(PythonError error);

        void onPythonSucceeded(PythonObject result);
    }

    private PythonInterpreter() {
    }

    private native void addArgumentBooleanInternal(long handle, boolean value);

    private native void addArgumentFloatInternal(long handle, float value);

    private native void addArgumentIntegerInternal(long handle, int value);

    private native void addArgumentStringInternal(long handle, String value);

    private native long allocateArgumentsHandleInternal();

    public final native long allocateHandleInternal();

    private native void cancelFunctionInternal(UUID uuid);

    private native void deallocateArgumentsHandleInternal(long handle);

    public final native void deallocateHandleInternal(long handle);

    private native UUID executeAsyncFunctionInternal(String function, String script, Object callback, long argumentPack);

    private native void executeFunctionInternal(String function, String script, Object callback, long argumentPack);

    public final native double getDoubleInternal(long handle);

    public final native double getDoubleWithDefaultValueInternal(long handle, double defaultValue);

    public final native PythonObject getObjectInternal(long handle, String name);

    public final native String getStringInternal(long handle);

    public final native String getStringWithDefaultValueInternal(long handle, String defaultValue);

    public final native boolean isValidInternal(long handle);

    private long createArguments(Object... arguments) throws ArgumentsCreationException {
        long handle = allocateArgumentsHandleInternal();
        for (Object arg : arguments) {
            if (arg instanceof Integer) {
                addArgumentIntegerInternal(handle, (Integer) arg);
            } else if (arg instanceof Boolean) {
                addArgumentBooleanInternal(handle, (Boolean) arg);
            } else if (arg instanceof Float) {
                addArgumentFloatInternal(handle, (Float) arg);
            } else if (arg instanceof String) {
                addArgumentStringInternal(handle, (String) arg);
            } else {
                destroyArguments(handle);
                throw new ArgumentsCreationException("Unknown argument type: "
                        + (arg == null ? "null" : arg.getClass().getSimpleName()));
            }
        }
        return handle;
    }

    private void destroyArguments(long handle) {
        if (handle != 0) {
            deallocateArgumentsHandleInternal(handle);
        }
    }

    public static void executeFunction(String function, String script, Listener listener, Object... arguments)
            throws ArgumentsCreationException {
        long args = INSTANCE.createArguments(arguments);
        INSTANCE.executeFunctionInternal(function, script, listener, args);
        INSTANCE.destroyArguments(args);
    }

    public UUID executeAsyncFunction(String function, String script, Listener listener, Object... arguments)
            throws ArgumentsCreationException {
        long args = createArguments(arguments);
        UUID id = executeAsyncFunctionInternal(function, script, listener, args);
        destroyArguments(args);
        return id;
    }

    public void cancelFunction(UUID uuid) {
        cancelFunctionInternal(uuid);
    }

    /** A Python value returned by the engine. The engine constructs these with the no-arg constructor. */
    public static final class PythonObject extends JniBinding {
        public PythonObject() {
            super(INSTANCE.allocateHandleInternal(), INSTANCE::deallocateHandleInternal, null);
        }

        public double getDouble() {
            return INSTANCE.getDoubleInternal(getNativeRef());
        }

        public double getDouble(double defaultValue) {
            return INSTANCE.getDoubleWithDefaultValueInternal(getNativeRef(), defaultValue);
        }

        public PythonObject getObject(String name) {
            return INSTANCE.getObjectInternal(getNativeRef(), name);
        }

        public String getString() {
            return INSTANCE.getStringInternal(getNativeRef());
        }

        public String getString(String defaultValue) {
            return INSTANCE.getStringWithDefaultValueInternal(getNativeRef(), defaultValue);
        }

        public boolean isValid() {
            return INSTANCE.isValidInternal(getNativeRef());
        }
    }
}
