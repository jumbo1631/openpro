package com.gopro.quikengine.model.livecontrol;

import com.gopro.quikengine.domain.ILiveControlWrapper;
import com.gopro.quikengine.utils.JniBinding;

/** Constructed by the engine's player; kept so the engine's class lookups resolve. */
public final class LiveControlWrapper extends JniBinding implements ILiveControlWrapper {
    private final String uid;
    private final Type type;
    private final ValidatorType validatorType;

    public LiveControlWrapper(long cppHandler, String uid, Type type, ValidatorType validatorType) {
        super(cppHandler, LiveControlWrapper::nativeDeleteRef, "LiveControlWrapper[" + uid + "]");
        this.uid = uid;
        this.type = type;
        this.validatorType = validatorType;
    }

    public static final native void nativeDeleteRef(long cppHandler);

    public static final native Object nativeGetLiveControlValidator(long cppHandler);

    public static final native Object nativeGetLiveControlValue(long cppHandler);

    public static final native boolean nativeLiveControlIsAvailable(long cppHandler, double time);

    public static final native <T> boolean nativeSetLiveControlValue(long cppHandler, T value);

    @Override
    public Object getCurrentValue() {
        return nativeGetLiveControlValue(getNativeRef());
    }

    @Override
    public Type getType() {
        return type;
    }

    @Override
    public String getUid() {
        return uid;
    }

    @Override
    public ValidatorType getValidatorType() {
        return validatorType;
    }

    @Override
    public boolean isAvailable(double time) {
        return nativeLiveControlIsAvailable(getNativeRef(), time);
    }

    public <T> void setCurrentValue(T value) {
        nativeSetLiveControlValue(getNativeRef(), value);
    }
}
