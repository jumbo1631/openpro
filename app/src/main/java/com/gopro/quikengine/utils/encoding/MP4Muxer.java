package com.gopro.quikengine.utils.encoding;

import java.io.IOException;
import java.nio.ByteBuffer;

/** FFmpeg-backed MP4 muxer living inside the engine. */
public class MP4Muxer {
    private long nativePtr;

    public MP4Muxer(String path) throws IOException {
        nativePtr = nativeCreate(path);
        if (nativePtr == 0) {
            throw new IOException("Could not create muxer context");
        }
    }

    public int addH264Stream(int width, int height, int[] timeBase, int bitRate, int level, ByteBuffer config,
                             int configSize) throws IOException {
        int track = nativeAddH264Stream(nativePtr, width, height, timeBase, bitRate, level, config, configSize);
        if (track != 0) {
            throw new IOException("Could not add h264 stream");
        }
        return track;
    }

    public int addHEVCStream(int width, int height, int[] timeBase, int bitRate, int level, ByteBuffer config,
                             int configSize) throws IOException {
        int track = nativeAddHEVCStream(nativePtr, width, height, timeBase, bitRate, level, config, configSize);
        if (track != 0) {
            throw new IOException("Could not add hevc stream");
        }
        return track;
    }

    public native int nativeAddH264Stream(long ptr, int width, int height, int[] timeBase, int bitRate, int level,
                                          ByteBuffer config, int configSize);

    public native int nativeAddHEVCStream(long ptr, int width, int height, int[] timeBase, int bitRate, int level,
                                          ByteBuffer config, int configSize);

    public native long nativeCreate(String path);

    public native int nativeRelease(long ptr);

    public native int nativeStart(long ptr);

    public native int nativeStop(long ptr);

    public native int nativeWriteFrame(long ptr, int track, ByteBuffer data, int size, long ptsUs, int keyFrame);

    public void release() {
        nativeRelease(nativePtr);
        nativePtr = 0L;
    }

    public void start() throws IOException {
        if (nativeStart(nativePtr) != 0) {
            throw new IOException("Could not start muxer");
        }
    }

    public void stop() throws IOException {
        if (nativeStop(nativePtr) != 0) {
            throw new IOException("Could not stop muxer");
        }
    }

    public void writeFrame(int track, ByteBuffer data, int size, long ptsUs, int keyFrame) throws IOException {
        if (nativeWriteFrame(nativePtr, track, data, size, ptsUs, keyFrame) != 0) {
            throw new IOException("Could not write frame");
        }
    }
}
