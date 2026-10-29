package com.gopro.quikengine.domain;

/** Player live controls (color, spherical framing). Only the types are needed for export. */
public interface ILiveControlWrapper {
    enum Type {
        FLOAT,
        INT,
        BOOL,
        VECTOR2,
        VECTOR3,
        VECTOR4,
        MATRIX
    }

    enum ValidatorType {
        RANGE,
        LIST
    }

    Object getCurrentValue();

    long getNativeRef();

    Type getType();

    String getUid();

    ValidatorType getValidatorType();

    boolean isAvailable(double time);
}
