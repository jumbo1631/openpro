package com.gopro.quikengine.utils;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class FileUtils {
    public static File createDirectory(File file) {
        if (!file.exists()) {
            file.mkdirs();
        }
        return file;
    }

    public static void createNoMediaFile(String dir) {
        try {
            new File(dir, ".nomedia").createNewFile();
        } catch (IOException e) {
            Log.w("QuikEngine", "createNoMediaFile", e);
        }
    }

    public static boolean deleteFile(String path) {
        return new File(path).delete();
    }

    public static void deleteRecursive(File file, boolean deleteSelf) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursive(child, true);
            }
        }
        if (deleteSelf) {
            file.delete();
        }
    }

    public static String mimeType(Context context, String uri) {
        return context.getContentResolver().getType(Uri.parse(uri));
    }

    public static void writeString(String content, File file) {
        createDirectory(file.getParentFile());
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.e("QuikEngine", "Failed to serialize object", e);
        }
    }
}
