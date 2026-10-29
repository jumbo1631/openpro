package com.gopro.quikengine.utils.encoding;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * AAC encoder the engine feeds PCM into from native code: getInputBuffer(), pushInputBuffer(),
 * stopRecording()/cancel(). Writes its own temporary MP4 that the engine muxes afterwards.
 */
public class MP4AudioEncoder extends Thread {
    private static final String TAG = "MP4AudioEncoder";
    private static final int TIMEOUT_THRESHOLD = 30_000_000;
    private static final int WAIT_TIMEOUT = 200_000;

    private final int bitRate;
    private MediaCodec.BufferInfo bufferInfo;
    private final String codec;
    private MediaCodec encoder;
    private boolean encoderStarted;
    private EndOfStream endOfStream;
    private boolean eosQueued;
    private final Object lock = new Object();
    private MediaMuxer muxer;
    private boolean muxerStarted;
    private final int nbChannels;
    private final String outputPath;
    private boolean ready;
    private final int sampleRate;
    private boolean started;
    private MediaFormat trackFormat;
    private int trackIndex;

    public enum EndOfStream {
        NO,
        SIGNALING,
        COMPLETED
    }

    public class MediaCodecInputBuffer {
        public ByteBuffer buffer;
        public int flags;
        public int index;
        public int offset;
        public long presentationTimeUs;
        public int size;

        public MediaCodecInputBuffer(MP4AudioEncoder encoder) {
        }
    }

    public MP4AudioEncoder(int nbChannels, int sampleRate, int bitRate, String codec, String outputPath) {
        this.nbChannels = nbChannels;
        this.sampleRate = sampleRate;
        this.bitRate = bitRate;
        this.codec = codec;
        this.outputPath = outputPath;
    }

    private void checkReadyState() {
        if (!started) {
            throw new IllegalStateException("Invalid state");
        }
        synchronized (lock) {
            while (!ready) {
                try {
                    lock.wait();
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    /** Returns 0 when work was done or the stream finished, otherwise the time waited (us). */
    private int drainEncoder(boolean blocking) throws Exception {
        int waited = 0;
        while (true) {
            synchronized (lock) {
                if (endOfStream == EndOfStream.SIGNALING && !eosQueued) {
                    // GoPro's build calls signalEndOfInputStream() here, which is only valid for
                    // surface input and throws for AAC; an empty EOS buffer flushes the tail instead.
                    int index = encoder.dequeueInputBuffer(WAIT_TIMEOUT);
                    if (index >= 0) {
                        encoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        eosQueued = true;
                    }
                }
            }
            int index = encoder.dequeueOutputBuffer(bufferInfo, WAIT_TIMEOUT);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!blocking) {
                    return WAIT_TIMEOUT;
                }
                waited += WAIT_TIMEOUT;
                if (waited > TIMEOUT_THRESHOLD) {
                    throw new MP4EncoderException("encoder is not supported");
                }
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) {
                    throw new IllegalStateException("Format changed twice");
                }
                trackFormat = encoder.getOutputFormat();
                trackIndex = muxer.addTrack(trackFormat);
                muxer.start();
                muxerStarted = true;
            } else if (index >= 0) {
                ByteBuffer output = encoder.getOutputBuffer(index);
                if (output == null) {
                    throw new IllegalStateException("Got null output buffer at index " + index);
                }
                if (trackFormat != null && !muxerStarted) {
                    trackIndex = muxer.addTrack(trackFormat);
                    muxer.start();
                    muxerStarted = true;
                }
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0;
                }
                if (bufferInfo.size != 0) {
                    if (!muxerStarted) {
                        throw new IllegalStateException("MediaMuxer was not started");
                    }
                    muxer.writeSampleData(trackIndex, output, bufferInfo);
                }
                encoder.releaseOutputBuffer(index, false);
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    synchronized (lock) {
                        endOfStream = EndOfStream.COMPLETED;
                    }
                    return 0;
                }
            } else {
                if (!blocking) {
                    return WAIT_TIMEOUT;
                }
                waited += WAIT_TIMEOUT;
                if (waited > TIMEOUT_THRESHOLD) {
                    throw new MP4EncoderException("encoder is not supported");
                }
            }
        }
    }

    private void release() {
        if (encoder != null) {
            if (encoderStarted) {
                try {
                    encoder.stop();
                } catch (Exception e) {
                    Log.w(TAG, "stop", e);
                }
                encoderStarted = false;
            }
            encoder.release();
            encoder = null;
        }
        if (muxer != null) {
            if (muxerStarted) {
                muxer.stop();
                muxerStarted = false;
            }
            muxer.release();
            muxer = null;
        }
    }

    private void setup() throws IOException {
        bufferInfo = new MediaCodec.BufferInfo();
        MediaFormat format = MediaFormat.createAudioFormat(codec, sampleRate, nbChannels);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, 2);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096);
        format.setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate);
        encoder = MediaCodec.createEncoderByType(codec);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();
        encoderStarted = true;
        muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        trackIndex = -1;
        muxerStarted = false;
    }

    public void cancel() {
        stopEncoder();
    }

    public MediaCodecInputBuffer getInputBuffer() {
        checkReadyState();
        int index = encoder.dequeueInputBuffer(-1L);
        if (index < 0) {
            return null;
        }
        MediaCodecInputBuffer input = new MediaCodecInputBuffer(this);
        input.index = index;
        input.buffer = encoder.getInputBuffer(index);
        return input;
    }

    public void pushInputBuffer(MediaCodecInputBuffer input, int size, double seconds) {
        checkReadyState();
        if (input != null) {
            long ptsUs = (long) (seconds * 1_000_000.0d);
            input.presentationTimeUs = ptsUs;
            input.size = size;
            encoder.queueInputBuffer(input.index, input.offset, size, ptsUs, input.flags);
        }
    }

    @Override
    public void run() {
        try {
            synchronized (lock) {
                setup();
                ready = true;
                lock.notifyAll();
            }
            int waited = 0;
            while (true) {
                int wait = drainEncoder(false);
                if (wait != 0) {
                    waited += wait;
                    if (waited > TIMEOUT_THRESHOLD) {
                        throw new MP4EncoderException("encoder is not supported");
                    }
                }
                synchronized (lock) {
                    if (endOfStream == EndOfStream.COMPLETED) {
                        return;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "audio encoding failed", e);
        } finally {
            release();
        }
    }

    @Override
    public void start() {
        started = true;
        endOfStream = EndOfStream.NO;
        super.start();
    }

    public void stopEncoder() {
        boolean signal;
        synchronized (lock) {
            signal = endOfStream == EndOfStream.NO;
            if (signal) {
                endOfStream = EndOfStream.SIGNALING;
            }
        }
        if (signal) {
            try {
                join();
            } catch (InterruptedException ignored) {
            }
        }
    }

    public void stopRecording() {
        synchronized (lock) {
            endOfStream = EndOfStream.SIGNALING;
        }
        try {
            join();
        } catch (InterruptedException ignored) {
        }
    }
}
