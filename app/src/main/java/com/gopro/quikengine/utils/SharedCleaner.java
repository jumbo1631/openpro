package com.gopro.quikengine.utils;

import android.util.Log;

import java.lang.ref.Cleaner;
import java.util.function.LongConsumer;

/** Releases native engine handles when their Java owner is garbage collected or closed. */
public final class SharedCleaner {
    public static final SharedCleaner INSTANCE = new SharedCleaner();

    private static final Cleaner CLEANER = Cleaner.create();

    public interface Cleanable {
        void clean();
    }

    public static final class RefCleanup implements Runnable {
        private final long nativeRef;
        private final LongConsumer destructor;
        private final String logTag;

        public RefCleanup(long nativeRef, LongConsumer destructor, String logTag) {
            this.nativeRef = nativeRef;
            this.destructor = destructor;
            this.logTag = logTag;
        }

        @Override
        public void run() {
            if (logTag != null) {
                Log.v("QECleaner", logTag + " cleans its nativeRef[" + nativeRef + "]");
            }
            destructor.accept(nativeRef);
        }
    }

    private SharedCleaner() {
    }

    public static Cleanable register(Object subject, Runnable cleanupAction) {
        Cleaner.Cleanable cleanable = CLEANER.register(subject, cleanupAction);
        return cleanable::clean;
    }

    public static Cleanable register(Object subject, long nativeRef, String logTag, LongConsumer destructor) {
        return register(subject, new RefCleanup(nativeRef, destructor, logTag));
    }
}
