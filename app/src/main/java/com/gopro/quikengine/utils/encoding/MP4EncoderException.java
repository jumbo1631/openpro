package com.gopro.quikengine.utils.encoding;

public class MP4EncoderException extends Exception {
    private static final String DEFAULT_MESSAGE = "MP4Encoder exception: ";

    public MP4EncoderException(String message) {
        super(DEFAULT_MESSAGE + message);
    }

    public MP4EncoderException(String message, Throwable cause) {
        super(DEFAULT_MESSAGE + message, cause);
    }
}
