package com.gopro.quikengine;

import com.gopro.quikengine.model.EngineError;
import com.gopro.quikengine.model.ExporterStage;
import com.gopro.quikengine.model.PerformanceError;
import com.gopro.quikengine.model.VideoCodec;
import com.gopro.quikengine.utils.JniBinding;

/** Renders an edit list (EDL JSON) to an MP4, applying the edits (stabilization included). */
public final class Exporter extends JniBinding {
    public interface Listener {
        void onCanceled();

        void onError(EngineError error);

        void onInformationAlert(String informationAlert);

        void onPerformanceAlert(PerformanceError error);

        void onProgress(float progress);

        void onSuccess(String filename);
    }

    public Exporter() {
        super(nativeExporterConstructor(), ref -> {
            nativeExporterRelease(ref);
            nativeExporterDestructor(ref);
        }, "Exporter");
        nativeExporterInitialize(getNativeRef());
    }

    public static boolean isDenoisingSupported() {
        return nativeExporterIsDenoiseSupported();
    }

    public static boolean isMotionBlurSupported() {
        return nativeExporterIsMotionBlurSupported();
    }

    private static native void nativeExporterCancel(long exporter);

    private static native long nativeExporterConstructor();

    private static native void nativeExporterDestructor(long exporter);

    private static native boolean nativeExporterInitialize(long exporter);

    private static native boolean nativeExporterIsDenoiseSupported();

    private static native boolean nativeExporterIsMotionBlurSupported();

    private static native boolean nativeExporterRelease(long exporter);

    private static native void nativeExporterSetBackend(long exporter, Object backend);

    private static native void nativeExporterSetHud(long exporter, boolean enable);

    private static native void nativeExporterSetHudConfig(long exporter, int scale, String filename,
                                                          int refreshRateDividend, int refreshRateDivider,
                                                          int windowSize);

    private static native void nativeExporterSetHudExportFilename(long exporter, String filename);

    private static native void nativeExporterSetHudMesureWindow(long exporter, int windowSize);

    private static native void nativeExporterSetHudRefreshRate(long exporter, int dividend, int divider);

    private static native void nativeExporterSetHudScale(long exporter, int scale);

    private static native void nativeExporterSetListener(long exporter, Object listener);

    private static native void nativeExporterSetPerformanceThreshold(long exporter, ExporterStage stage, double time);

    private static native boolean nativeExporterStart(long exporter, String output, String project, int width,
                                                      int height, String codec, int bitrate,
                                                      OutputProjection outputProjection, String framerate,
                                                      int denoiseStrength, int motionBlurStrength);

    public void cancel() {
        nativeExporterCancel(getNativeRef());
    }

    public void release() {
        close();
    }

    public void setBackend(Backend.Type backend) {
        nativeExporterSetBackend(getNativeRef(), backend);
    }

    public void setListener(Listener listener) {
        nativeExporterSetListener(getNativeRef(), listener);
    }

    public void setPerformanceThreshold(ExporterStage stage, double time) {
        nativeExporterSetPerformanceThreshold(getNativeRef(), stage, time);
    }

    /**
     * @param project the EDL JSON (GoPro passes QuikSingleClipInput.toJson() here)
     * @param framerate rational as "num/den", or "" to keep the source rate
     */
    public boolean start(String filename, String project, int width, int height, int bitrate, VideoCodec codec,
                         OutputProjection outputProjection, String framerate, int denoiseStrength,
                         int motionBlurStrength) {
        return nativeExporterStart(getNativeRef(), filename, project, width, height, codec.getMimeType(), bitrate,
                outputProjection, framerate, denoiseStrength, motionBlurStrength);
    }
}
