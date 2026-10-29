package com.gopro.quikengine.utils.encoding;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.util.Range;
import android.view.Surface;

import com.gopro.quikengine.model.VideoCodec;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;

/**
 * Surface-input video encoder the engine renders into. The engine drives it from native code:
 * constructor, start(), getSurface(), frameAvailable(seconds), stopRecording()/cancel().
 * Encoder settings mirror GoPro Quik 13.43 exactly.
 */
public class MP4VideoEncoder extends Thread {
    private static final String TAG = "MP4VideoEncoder";
    private static final int MSG_FRAME_AVAILABLE = 1;
    private static final int MSG_STOP_RECORDING = 2;
    private static final int MSG_STOP_ENCODER = 3;
    private static final int CODEC_US_PER_SECOND = 1_000_000;
    private static final int WAIT_TIMEOUT = 200_000;
    private static final int TIMEOUT_THRESHOLD = 30_000_000;

    private int bitRate;
    private final String codec;
    private MediaCodec encoder;
    private boolean encoderStarted;
    private boolean endOfStream;
    private boolean endOfStreamSignaled;
    private Exception exception;
    private final int[] frameRate;
    private Handler handler;
    private final int height;
    private MP4Muxer muxer;
    private boolean muxerStarted;
    private final String outputPath;
    private boolean ready;
    private boolean started;
    private Surface surface;
    private int trackIndex;
    private final int width;
    private final Object startLock = new Object();
    private final Object encoderLock = new Object();
    private final Object lock = new Object();
    private int dequeueOutputBufferCounter = 0;

    public static class EncoderHandler extends Handler {
        private final WeakReference<MP4VideoEncoder> encoderRef;

        public EncoderHandler(MP4VideoEncoder encoder) {
            encoderRef = new WeakReference<>(encoder);
        }

        @Override
        public void handleMessage(Message message) {
            MP4VideoEncoder encoder = encoderRef.get();
            if (encoder == null) {
                return;
            }
            switch (message.what) {
                case MSG_FRAME_AVAILABLE:
                    try {
                        encoder.checkError();
                        encoder.handleFrameAvailable((Double) message.obj);
                    } catch (Exception e) {
                        encoder.setError(e);
                    }
                    break;
                case MSG_STOP_RECORDING:
                    encoder.handleStopRecording();
                    removeMessages(MSG_FRAME_AVAILABLE);
                    removeMessages(MSG_STOP_RECORDING);
                    break;
                case MSG_STOP_ENCODER:
                    encoder.handleStopEncoder();
                    encoder.setStop();
                    break;
                default:
                    throw new IllegalStateException("Unhandled msg what=" + message.what);
            }
        }
    }

    public class OutputBufferInfo {
        ByteBuffer buffer;
        public MediaCodec.BufferInfo bufferInfo;
        public int index;
    }

    public MP4VideoEncoder(int width, int height, int[] frameRate, int bitRate, String codec, String outputPath) {
        this.width = width;
        this.height = height;
        this.bitRate = bitRate;
        this.frameRate = frameRate;
        this.codec = codec;
        this.outputPath = outputPath;
    }

    private void checkError() throws Exception {
        synchronized (lock) {
            if (exception != null) {
                throw exception;
            }
            lock.notify();
        }
    }

    private void checkState() {
        if (!started) {
            throw new IllegalStateException("Invalid state");
        }
        synchronized (lock) {
            while (!ready && exception == null) {
                try {
                    lock.wait();
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    private void ensureStopped() {
        synchronized (startLock) {
            while (started) {
                try {
                    startLock.wait();
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    private OutputBufferInfo getOutputBufferInfo() throws Exception {
        OutputBufferInfo info = new OutputBufferInfo();
        info.bufferInfo = new MediaCodec.BufferInfo();
        info.index = -1;
        dequeueOutputBufferCounter = 0;
        int waited = 0;
        while (true) {
            if (info.index >= 0) {
                dequeueOutputBufferCounter = 0;
                synchronized (encoderLock) {
                    info.buffer = encoder.getOutputBuffer(info.index);
                }
                if (info.buffer == null) {
                    throw new RuntimeException("Got null output buffer at index " + info.index);
                }
                if ((info.bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    synchronized (lock) {
                        endOfStream = true;
                        lock.notify();
                    }
                }
                return info;
            }
            synchronized (encoderLock) {
                info.index = encoder.dequeueOutputBuffer(info.bufferInfo, WAIT_TIMEOUT);
            }
            if (info.index < 0) {
                dequeueOutputBufferCounter++;
                waited += WAIT_TIMEOUT;
                if (waited > TIMEOUT_THRESHOLD) {
                    throw new MP4EncoderException("encoder is not supported");
                }
            }
        }
    }

    private void handleFrameAvailable(double seconds) throws Exception {
        if (endOfStream) {
            return;
        }
        OutputBufferInfo info = getOutputBufferInfo();
        if ((info.bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 && !muxerStarted) {
            int[] timeBase = {1, CODEC_US_PER_SECOND};
            if (codec.contentEquals(VideoCodec.H264.getMimeType())) {
                trackIndex = muxer.addH264Stream(width, height, timeBase, bitRate, 16, info.buffer, info.bufferInfo.size);
            } else if (codec.contentEquals(VideoCodec.HEVC.getMimeType())) {
                trackIndex = muxer.addHEVCStream(width, height, timeBase, bitRate, 16, info.buffer, info.bufferInfo.size);
            } else {
                throw new IllegalArgumentException("Unsupported codec " + codec);
            }
            muxer.start();
            muxerStarted = true;
            synchronized (encoderLock) {
                encoder.releaseOutputBuffer(info.index, false);
            }
            info = getOutputBufferInfo();
        }
        if (info.bufferInfo.size > 0) {
            if (!muxerStarted) {
                throw new IllegalStateException("MediaMuxer was not started");
            }
            int keyFrame = info.bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME;
            muxer.writeFrame(trackIndex, info.buffer, info.bufferInfo.size, Math.round(seconds * 1_000_000.0d), keyFrame);
        }
        synchronized (encoderLock) {
            encoder.releaseOutputBuffer(info.index, false);
        }
    }

    private void handleStopEncoder() {
        Looper looper = Looper.myLooper();
        if (looper != null) {
            looper.quit();
        }
    }

    private void handleStopRecording() {
        if (endOfStream) {
            return;
        }
        releaseMuxer();
        synchronized (lock) {
            endOfStream = true;
            lock.notify();
        }
    }

    private void release() {
        releaseEncoder();
        releaseMuxer();
    }

    private void releaseEncoder() {
        if (surface != null) {
            surface.release();
            surface = null;
        }
        if (encoder != null) {
            if (encoderStarted) {
                try {
                    encoder.stop();
                } catch (Exception e) {
                    Log.w(TAG, "stop", e);
                }
                encoderStarted = false;
            }
            synchronized (encoderLock) {
                encoder.release();
                encoder = null;
            }
        }
    }

    private void releaseMuxer() {
        if (muxer != null) {
            if (muxerStarted) {
                try {
                    muxer.stop();
                } catch (Exception e) {
                    Log.w(TAG, "muxer stop", e);
                }
                muxerStarted = false;
            }
            muxer.release();
            muxer = null;
        }
    }

    private void setError(Exception e) {
        synchronized (lock) {
            exception = e;
            lock.notify();
        }
    }

    private void setReady() {
        synchronized (lock) {
            ready = true;
            lock.notify();
        }
    }

    private void setStop() {
        synchronized (startLock) {
            started = false;
            startLock.notify();
        }
    }

    private void setup() throws Exception {
        try {
            setupEncoder(true);
        } catch (Exception e) {
            // Some H.264 encoders reject the explicit profile/level; retry without them.
            releaseEncoder();
            setupEncoder(false);
        }
        muxer = new MP4Muxer(outputPath);
        trackIndex = -1;
    }

    private void setupEncoder(boolean withProfile) throws IOException {
        encoder = MediaCodec.createEncoderByType(codec);
        MediaFormat format = MediaFormat.createVideoFormat(codec, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
        format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
        format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        Range<Integer> bitrateRange = encoder.getCodecInfo().getCapabilitiesForType(codec)
                .getVideoCapabilities().getBitrateRange();
        if (!bitrateRange.contains(bitRate)) {
            bitRate = bitrateRange.clamp(bitRate);
        }
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        int fps = (int) Math.floor((double) frameRate[0] / frameRate[1]);
        String device = Build.DEVICE;
        if ((device.equals("blueline") || device.equals("crosshatch")) && (width >= 3840 || height >= 3840)) {
            fps = 30;
        }
        format.setInteger(MediaFormat.KEY_FRAME_RATE, Math.min(fps, 60));
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        if (codec.contentEquals(VideoCodec.H264.getMimeType()) && withProfile) {
            format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel42);
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
        }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        surface = encoder.createInputSurface();
        encoder.start();
        encoderStarted = true;
    }

    public void cancel() {
        stopEncoder();
    }

    public void frameAvailable(double seconds) throws Exception {
        checkState();
        checkError();
        handler.sendMessage(handler.obtainMessage(MSG_FRAME_AVAILABLE, seconds));
    }

    public int getDequeOutputBufferCount() {
        return dequeueOutputBufferCounter;
    }

    public int getLastEncoderError() {
        synchronized (lock) {
            if (exception instanceof MediaCodec.CodecException) {
                return ((MediaCodec.CodecException) exception).getErrorCode();
            }
            return 0;
        }
    }

    public Surface getSurface() throws Exception {
        checkState();
        checkError();
        return surface;
    }

    @Override
    public void run() {
        try {
            synchronized (lock) {
                Looper.prepare();
                handler = new EncoderHandler(this);
                setup();
                setReady();
            }
            Looper.loop();
        } catch (Exception e) {
            setError(e);
        } finally {
            release();
        }
    }

    @Override
    public void start() {
        started = true;
        super.start();
    }

    public void stopEncoder() {
        checkState();
        synchronized (encoderLock) {
            if (!endOfStreamSignaled && encoder != null) {
                encoder.signalEndOfInputStream();
                endOfStreamSignaled = true;
            }
        }
        handler.sendMessage(handler.obtainMessage(MSG_STOP_ENCODER));
        ensureStopped();
    }

    public void stopRecording() throws Exception {
        checkState();
        checkError();
        synchronized (encoderLock) {
            encoder.signalEndOfInputStream();
            endOfStreamSignaled = true;
        }
        handler.sendMessage(handler.obtainMessage(MSG_STOP_RECORDING));
        synchronized (lock) {
            while (!endOfStream) {
                try {
                    lock.wait();
                } catch (InterruptedException ignored) {
                }
                checkError();
            }
        }
    }
}
