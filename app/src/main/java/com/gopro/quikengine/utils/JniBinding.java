package com.gopro.quikengine.utils;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongConsumer;

/** Owns one native engine handle; the field names are read by the engine. */
public abstract class JniBinding implements AutoCloseable {
    private final SharedCleaner.Cleanable cleanable;
    private final AtomicBoolean isClosed;
    private final long nativeRef;

    public JniBinding(long nativeRef, LongConsumer destructor, String logTag) {
        this.nativeRef = nativeRef;
        this.isClosed = new AtomicBoolean(false);
        this.cleanable = SharedCleaner.register(this, nativeRef, logTag, destructor);
    }

    @Override
    public void close() {
        if (isClosed.compareAndSet(false, true)) {
            cleanable.clean();
        }
    }

    public final long getNativeRef() {
        return nativeRef;
    }

    public final AtomicBoolean isClosed() {
        return isClosed;
    }
}
