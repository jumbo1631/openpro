package com.gopro.quikengine;

import android.content.Context;
import android.util.Log;

import com.gopro.quikengine.model.PythonError;
import com.gopro.quikengine.services.PythonInterpreter;
import com.gopro.quikengine.utils.PackageManager;

/** Loads the engine libraries and installs its data bundle, in the same order as GoPro Quik. */
public final class QuikEngine {
    private static final String TAG = "QuikEngine";
    private static volatile boolean isInstalled = false;
    private static boolean pipesStarted = false;

    private static final String[] LIBRARIES = {
        "fribidi", "avutil", "avcodec", "avformat", "avdevice", "swresample", "swscale", "avfilter", "native-lib",
    };

    static {
        java.io.File dir = EngineLocation.libDir;
        if (dir == null) {
            throw new UnsatisfiedLinkError("GoPro's engine hasn't been added");
        }
        loadFrom(dir);
    }

    /**
     * Loads the engine from a folder in the app's storage. Android finds a library's dependencies
     * only among libraries already loaded, so the order matters; it isn't fixed here, but found by
     * loading what can be loaded, round after round, until all are in (or a round loads nothing).
     */
    private static void loadFrom(java.io.File dir) {
        java.util.List<String> left = new java.util.ArrayList<>();
        left.add("c++_shared");
        java.util.Collections.addAll(left, LIBRARIES);
        UnsatisfiedLinkError last = null;
        while (!left.isEmpty()) {
            boolean progress = false;
            for (java.util.Iterator<String> it = left.iterator(); it.hasNext(); ) {
                java.io.File file = new java.io.File(dir, "lib" + it.next() + ".so");
                if (!file.exists()) {
                    it.remove();
                    continue;
                }
                try {
                    System.load(file.getAbsolutePath());
                    it.remove();
                    progress = true;
                } catch (UnsatisfiedLinkError e) {
                    last = e;
                }
            }
            if (!progress && !left.isEmpty()) {
                throw last != null ? last : new UnsatisfiedLinkError("Couldn't load " + left);
            }
        }
    }

    private QuikEngine() {
    }

    public static String getBuildInfo() {
        return nativeGetBuildInfo();
    }

    public static String getBuildNumber() {
        return nativeGetBuildNumber();
    }

    public static native String getBundlePath();

    public static native String getCachePath();

    private static native String nativeGetBuildInfo();

    private static native String nativeGetBuildNumber();

    private static native void nativePipeSTDErrToLogcat();

    private static native void nativePipeSTDOutToLogcat();

    /**
     * Installs qe_bundle and warms up the Python director, blocking the caller. GoPro's app runs
     * this same sequence on a background thread from QuikEngine.load().
     */
    public static synchronized boolean loadBlocking(Context context) {
        redirectStdoutToLogcat();
        if (isInstalled) {
            return true;
        }
        isInstalled = PackageManager.Initialize(context);
        if (!isInstalled) {
            return false;
        }
        try {
            PythonInterpreter.executeFunction("dummy_preload", "director.api.mobile", new PythonInterpreter.Listener() {
                @Override
                public void onPythonFailed(PythonError error) {
                    Log.w(TAG, "dummy_preload failed: " + error.getMessage());
                }

                @Override
                public void onPythonSucceeded(PythonInterpreter.PythonObject result) {
                }
            });
        } catch (PythonInterpreter.ArgumentsCreationException e) {
            Log.w(TAG, "dummy_preload", e);
        }
        return true;
    }

    private static void redirectStdoutToLogcat() {
        if (pipesStarted) {
            return;
        }
        pipesStarted = true;
        new Thread(QuikEngine::nativePipeSTDOutToLogcat, "qe-stdout").start();
        new Thread(QuikEngine::nativePipeSTDErrToLogcat, "qe-stderr").start();
    }
}
