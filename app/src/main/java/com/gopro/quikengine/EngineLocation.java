package com.gopro.quikengine;

import java.io.File;

/**
 * Where the engine is: the folder OpenPro copied it into from a GoPro Quik file the user picked on
 * the phone (com.openpro.quik.EngineFiles). Set before anything touches QuikEngine.
 */
public final class EngineLocation {
    /** The engine's .so files. */
    public static volatile File libDir;
    /** The qe_bundle folder (what Quik has under assets/qe_bundle). */
    public static volatile File bundleDir;

    private EngineLocation() {
    }
}
