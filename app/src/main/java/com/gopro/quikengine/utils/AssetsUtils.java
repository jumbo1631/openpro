package com.gopro.quikengine.utils;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Copies the qe_bundle tree (added on the phone, see EngineFiles) into the engine's install directory. */
public class AssetsUtils {
    private static final String TAG = "QuikEngine";

    /** Whether every file of the bundle is already installed, at the same size. */
    public static boolean checkFolderIntegrity(File source, String installPath) {
        File installed = new File(installPath);
        if (!source.isDirectory()) {
            return installed.exists() && installed.length() == source.length();
        }
        File[] children = source.listFiles();
        if (children == null) {
            return false;
        }
        for (File child : children) {
            if (!checkFolderIntegrity(child, installPath + File.separator + child.getName())) {
                return false;
            }
        }
        return true;
    }

    public static boolean copyFolder(File source, String outPath) {
        if (!source.isDirectory()) {
            try (InputStream in = new FileInputStream(source);
                 FileOutputStream out = new FileOutputStream(outPath)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                return true;
            } catch (IOException e) {
                Log.w(TAG, "copyFolder " + source, e);
                return false;
            }
        }
        File dir = new File(outPath);
        boolean ok = dir.isDirectory() || dir.mkdirs();
        File[] children = source.listFiles();
        if (children == null) {
            return false;
        }
        for (File child : children) {
            ok &= copyFolder(child, outPath + File.separator + child.getName());
        }
        return ok;
    }
}
