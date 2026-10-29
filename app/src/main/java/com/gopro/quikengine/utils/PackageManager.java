package com.gopro.quikengine.utils;

import android.content.Context;

import java.io.File;

/** Installs qe_bundle where the engine expects it (path chosen by the engine). */
public class PackageManager {
    public static boolean Initialize(Context context) {
        String installPath = getInstallPath();
        File install = new File(installPath);
        File parent = install.getParentFile();
        File[] siblings = parent != null ? parent.listFiles() : null;
        if (siblings != null) {
            for (File sibling : siblings) {
                if (!sibling.isDirectory()) {
                    continue;
                }
                boolean legacy = sibling.getName().equals("data_1");
                boolean stale = sibling.getName().contains("quikengine_data_") && !sibling.getName().equals(install.getName());
                if (legacy || stale) {
                    FileUtils.deleteRecursive(sibling, true);
                }
            }
        }
        // The folder the engine was copied into on the phone.
        File bundle = com.gopro.quikengine.EngineLocation.bundleDir;
        if (bundle == null) {
            return false;
        }
        if (install.exists()) {
            if (!installPath.contains("_dirty") && AssetsUtils.checkFolderIntegrity(bundle, installPath)) {
                return true;
            }
            FileUtils.deleteRecursive(install, true);
        }
        if (!AssetsUtils.copyFolder(bundle, installPath)) {
            return false;
        }
        FileUtils.createNoMediaFile(installPath);
        return true;
    }

    private static native String getInstallPath();
}
