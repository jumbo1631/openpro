package com.gopro.quikengine.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PointF;
import android.media.FaceDetector;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;

/**
 * Image helpers the engine calls during media analysis. GoPro's build used Glide and Play
 * Services face detection; these use platform APIs and produce the same outputs.
 */
public class ImageUtils {
    public static final int FACE_DETECTOR_IMAGE_SIZE = 350;
    private static final String TAG = "QuikEngine";
    private static final int MAX_FACES = 16;

    public static void createVideoThumbnail(Context context, String source, String outPath, int width, int height,
                                            long timeUs) throws Exception {
        Bitmap bitmap;
        if (timeUs < 0) {
            bitmap = getBitmap(context, source, Math.max(width, height));
        } else {
            MediaMetadataRetriever retriever = openRetriever(context, source);
            try {
                bitmap = retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, width, height);
            } finally {
                retriever.release();
            }
            if (bitmap == null) {
                throw new IllegalStateException("no frame at " + timeUs + "us in " + source);
            }
        }
        try (FileOutputStream out = new FileOutputStream(outPath)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 99, out);
        }
    }

    public static void resizeImage(Context context, String source, String outPath, int maxSize, boolean unused)
            throws Exception {
        Bitmap bitmap = getBitmap(context, source, maxSize);
        try (FileOutputStream out = new FileOutputStream(outPath)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 99, out);
        }
    }

    public static void faceRecognitionTask(Context context, String source, String outPath) throws Exception {
        Bitmap decoded = getBitmap(context, source.split("\\?", 2)[0], FACE_DETECTOR_IMAGE_SIZE);
        // android.media.FaceDetector needs RGB_565 and an even width.
        int evenWidth = decoded.getWidth() & ~1;
        Bitmap bitmap = Bitmap.createBitmap(decoded, 0, 0, evenWidth, decoded.getHeight()).copy(Bitmap.Config.RGB_565, false);
        float width = bitmap.getWidth();
        float height = bitmap.getHeight();
        FaceDetector.Face[] faces = new FaceDetector.Face[MAX_FACES];
        int found = new FaceDetector(bitmap.getWidth(), bitmap.getHeight(), MAX_FACES).findFaces(bitmap, faces);
        JSONArray result = new JSONArray();
        PointF mid = new PointF();
        for (int i = 0; i < found; i++) {
            FaceDetector.Face face = faces[i];
            face.getMidPoint(mid);
            float eyes = face.eyesDistance();
            // Approximate a face box from the eye line, as the Play Services detector reports one.
            float left = mid.x - eyes * 1.2f;
            float top = mid.y - eyes * 1.0f;
            float right = mid.x + eyes * 1.2f;
            float bottom = mid.y + eyes * 1.8f;
            JSONObject entry = new JSONObject();
            JSONArray coord = new JSONArray();
            coord.put(Math.max(0f, left) / width);
            coord.put(Math.max(0f, top) / height);
            coord.put(Math.min(width, right) / width);
            coord.put(Math.min(height, bottom) / height);
            entry.put("coord", coord);
            result.put(entry);
        }
        FileUtils.writeString(result.toString(), new File(outPath));
    }

    public static Bitmap getBitmap(Context context, String source, int maxSize) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = open(context, source)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        Bitmap bitmap;
        if (bounds.outWidth > 0) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            int largest = Math.max(bounds.outWidth, bounds.outHeight);
            options.inSampleSize = Math.max(1, Integer.highestOneBit(Math.max(1, largest / maxSize)));
            try (InputStream in = open(context, source)) {
                bitmap = BitmapFactory.decodeStream(in, null, options);
            }
        } else {
            // Not an image: take the first video frame.
            MediaMetadataRetriever retriever = openRetriever(context, source);
            try {
                bitmap = retriever.getFrameAtTime(0);
            } finally {
                retriever.release();
            }
        }
        if (bitmap == null) {
            throw new IllegalStateException("cannot decode " + source);
        }
        float scale = Math.min(1f, (float) maxSize / Math.max(bitmap.getWidth(), bitmap.getHeight()));
        if (scale < 1f) {
            bitmap = Bitmap.createScaledBitmap(bitmap, Math.round(bitmap.getWidth() * scale),
                    Math.round(bitmap.getHeight() * scale), true);
        }
        return bitmap;
    }

    private static InputStream open(Context context, String source) throws Exception {
        Uri uri = Uri.parse(source);
        String scheme = uri.getScheme();
        if (scheme == null || scheme.equals("file")) {
            return new java.io.FileInputStream(scheme == null ? source : uri.getPath());
        }
        if (scheme.startsWith("http")) {
            return new java.net.URL(source).openStream();
        }
        return context.getContentResolver().openInputStream(uri);
    }

    private static MediaMetadataRetriever openRetriever(Context context, String source) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Uri uri = Uri.parse(source);
        String scheme = uri.getScheme();
        if (scheme == null) {
            retriever.setDataSource(source);
        } else if (scheme.startsWith("http")) {
            retriever.setDataSource(source, new HashMap<>());
        } else {
            retriever.setDataSource(context, uri);
        }
        Log.v(TAG, "retriever opened " + source);
        return retriever;
    }
}
