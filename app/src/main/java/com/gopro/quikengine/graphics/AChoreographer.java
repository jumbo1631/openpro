package com.gopro.quikengine.graphics;

import android.os.HandlerThread;
import android.os.Process;
import android.view.Choreographer;

import java.util.concurrent.CountDownLatch;

/** Vsync source the engine's player creates from native code. */
public class AChoreographer implements Choreographer.FrameCallback {
    private static final String TAG = "Achoreographer";

    private Choreographer choreographer;
    private final CountDownLatch initLatch = new CountDownLatch(1);
    private final long nativePtr;
    private boolean started;
    private final HandlerThread thread;

    public AChoreographer(long nativePtr) {
        this.nativePtr = nativePtr;
        thread = new HandlerThread(TAG, Process.THREAD_PRIORITY_DISPLAY) {
            @Override
            protected void onLooperPrepared() {
                choreographer = Choreographer.getInstance();
                initLatch.countDown();
            }
        };
        thread.start();
        try {
            initLatch.await();
        } catch (InterruptedException ignored) {
        }
    }

    @Override
    public synchronized void doFrame(long frameTimeNanos) {
        if (started) {
            choreographer.postFrameCallback(this);
            nativeDoFrame(nativePtr, frameTimeNanos);
        }
    }

    public native void nativeDoFrame(long ptr, long frameTimeNanos);

    public void release() {
        synchronized (this) {
            started = false;
            choreographer.removeFrameCallback(this);
        }
        thread.quitSafely();
    }

    public synchronized void start() {
        started = true;
        choreographer.postFrameCallback(this);
    }

    public synchronized void stop() {
        started = false;
        choreographer.removeFrameCallback(this);
    }
}
