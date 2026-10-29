package com.gopro.quikengine.services;

import com.gopro.quikengine.model.PythonError;

import java.util.UUID;

/** Typed entry points into the engine's Python API (feature queries, settings). */
public final class PythonEngine {
    public static final PythonEngine INSTANCE = new PythonEngine();

    public static final class ArgumentsCreationException extends Exception {
        public ArgumentsCreationException(String message) {
            super(message);
        }
    }

    public interface Listener {
        void onPythonFailed(PythonError error);

        void onPythonSucceeded(PythonInterpreter.PythonObject result);
    }

    private PythonEngine() {
    }

    public static final native void getCloudExportSettings(Listener listener, String hardwareCapabilities, String edl,
                                                           String outputProjection) throws ArgumentsCreationException;

    public static final native UUID getCloudExportSettingsAsync(Listener listener, String hardwareCapabilities,
                                                                String edl, String outputProjection)
            throws ArgumentsCreationException;

    public static final native void getEngineSettings(Listener listener) throws ArgumentsCreationException;

    /**
     * @param timelineElementIds JSON array of timeline_element_id strings
     * @param features JSON array of feature names, e.g. ["suggested_stabilization_settings"]
     */
    public static final native void getMediaFeatures(Listener listener, String edl, String timelineElementIds,
                                                     String features) throws ArgumentsCreationException;

    public static final native UUID getMediaFeaturesAsync(Listener listener, String edl, String timelineElementIds,
                                                          String features) throws ArgumentsCreationException;

    public static final native void setEngineSettings(Listener listener, String newSettings)
            throws ArgumentsCreationException;

    public void cancelFunction(UUID uuid) {
        PythonInterpreter.INSTANCE.cancelFunction(uuid);
    }
}
