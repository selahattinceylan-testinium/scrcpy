package com.genymobile.scrcpy.video;

import com.genymobile.scrcpy.AndroidVersions;
import com.genymobile.scrcpy.AsyncProcessor;
import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.device.ConfigurationException;
import com.genymobile.scrcpy.device.Size;
import com.genymobile.scrcpy.device.Streamer;
import com.genymobile.scrcpy.util.Codec;
import com.genymobile.scrcpy.util.CodecOption;
import com.genymobile.scrcpy.util.CodecUtils;
import com.genymobile.scrcpy.util.IO;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.util.LogUtils;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class SurfaceEncoder implements AsyncProcessor {

    private static final int DEFAULT_I_FRAME_INTERVAL = 10; // seconds
    private static final int REPEAT_FRAME_DELAY_US = 100_000; // repeat after 100ms
    private static final long DEQUEUE_TIMEOUT_US = 1_000_000; // 1 second
    private static final int PREPARE_RETRY_DELAY_MS = 200;
    private static final int PREPARE_RETRY_COUNT = 3;
    private static final int POST_RESET_SETTLE_DELAY_MS = 100;
    private static final String KEY_MAX_FPS_TO_ENCODER = "max-fps-to-encoder";

    // Keep the values in descending order
    private static final int[] MAX_SIZE_FALLBACK = {2560, 1920, 1600, 1280, 1024, 800};
    private static final int MAX_CONSECUTIVE_ERRORS = 3;

    private final SurfaceCapture capture;
    private final Streamer streamer;
    private final String encoderName;
    private final List<CodecOption> codecOptions;
    private final int videoBitRate;
    private final float maxFps;
    private final boolean downsizeOnError;

    private boolean firstFrameSent;
    private int consecutiveErrors;

    private Thread thread;
    private final AtomicBoolean stopped = new AtomicBoolean();

    private final CaptureReset reset = new CaptureReset();

    public SurfaceEncoder(SurfaceCapture capture, Streamer streamer, Options options) {
        this.capture = capture;
        this.streamer = streamer;
        this.videoBitRate = options.getVideoBitRate();
        this.maxFps = options.getMaxFps();
        this.codecOptions = options.getVideoCodecOptions();
        this.encoderName = options.getVideoEncoder();
        this.downsizeOnError = options.getDownsizeOnError();
    }

    /**
     * Retry capture.prepare() with delays after a reset.
     * <p/>
     * On some devices (e.g. Samsung), the display may be temporarily unavailable during rotation,
     * causing prepare() to fail with ConfigurationException. Retrying after a short delay allows
     * the display to become available again.
     */
    private void prepareWithRetry() throws ConfigurationException, IOException {
        for (int attempt = 1; attempt <= PREPARE_RETRY_COUNT; attempt++) {
            try {
                capture.prepare();
                return;
            } catch (ConfigurationException e) {
                if (attempt < PREPARE_RETRY_COUNT) {
                    Ln.w("Prepare failed after reset (attempt " + attempt + "/" + PREPARE_RETRY_COUNT + "), retrying...");
                    SystemClock.sleep(PREPARE_RETRY_DELAY_MS);
                } else {
                    throw e;
                }
            }
        }
    }

    private void streamCapture() throws IOException, ConfigurationException {
        Codec codec = streamer.getCodec();
        MediaCodec mediaCodec = createMediaCodec(codec, encoderName);
        MediaFormat format = createFormat(codec.getMimeType(), videoBitRate, maxFps, codecOptions);

        capture.init(reset);

        try {
            boolean alive;
            boolean headerWritten = false;

            do {
                boolean wasReset = reset.consumeReset(); // If a capture reset was requested, it is implicitly fulfilled
                if (wasReset) {
                    Ln.d("Capture reset consumed, re-preparing capture");

                    // Recreate the MediaCodec to avoid potential encoder state issues after rotation.
                    // Some devices (e.g. Samsung Exynos) do not properly handle reconfiguration of
                    // the same MediaCodec instance with a different resolution after stop/reset.
                    MediaCodec oldCodec = mediaCodec;
                    mediaCodec = null;
                    oldCodec.release();
                    mediaCodec = createMediaCodec(codec, encoderName);

                    prepareWithRetry();

                    // Brief delay to let the display settle after rotation
                    SystemClock.sleep(POST_RESET_SETTLE_DELAY_MS);
                } else {
                    capture.prepare();
                }
                Size size = capture.getSize();
                if (!headerWritten) {
                    streamer.writeVideoHeader(size);
                    headerWritten = true;
                }

                format.setInteger(MediaFormat.KEY_WIDTH, size.getWidth());
                format.setInteger(MediaFormat.KEY_HEIGHT, size.getHeight());

                Surface surface = null;
                boolean mediaCodecStarted = false;
                boolean captureStarted = false;
                try {
                    mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    surface = mediaCodec.createInputSurface();

                    capture.start(surface);
                    captureStarted = true;

                    mediaCodec.start();
                    mediaCodecStarted = true;

                    // Reset error counter — successfully restarted after rotation
                    consecutiveErrors = 0;

                    // Set the MediaCodec instance to "interrupt" (by signaling an EOS) on reset
                    reset.setRunningMediaCodec(mediaCodec);

                    if (stopped.get()) {
                        Ln.d("Encoder stopped before encoding started");
                        alive = false;
                    } else {
                        boolean resetRequested = reset.consumeReset();
                        if (resetRequested) {
                            Ln.d("Reset requested before encoding, skipping encode and restarting");
                        } else {
                            // If a reset is requested during encode(), it will interrupt the encoding by an EOS
                            encode(mediaCodec, streamer);
                        }
                        // The capture might have been closed internally (for example if the camera is disconnected)
                        alive = !stopped.get() && !capture.isClosed();
                        if (!alive) {
                            Ln.d("Streaming loop ending: stopped=" + stopped.get() + ", closed=" + capture.isClosed());
                        }
                    }
                } catch (IllegalStateException | IllegalArgumentException | IOException e) {
                    if (IO.isBrokenPipe(e)) {
                        // Do not retry on broken pipe, which is expected on close because the socket is closed by the client
                        throw e;
                    }
                    Ln.e("Capture/encoding error (consecutiveErrors=" + consecutiveErrors + "): " + e.getClass().getName() + ": " + e.getMessage());
                    if (!prepareRetry(size)) {
                        Ln.e("Giving up after " + consecutiveErrors + " consecutive errors");
                        throw e;
                    }
                    Ln.d("Retrying capture/encoding (consecutiveErrors=" + consecutiveErrors + ")");
                    alive = true;
                } finally {
                    reset.setRunningMediaCodec(null);
                    if (captureStarted) {
                        capture.stop();
                    }
                    if (mediaCodecStarted) {
                        try {
                            mediaCodec.stop();
                        } catch (IllegalStateException e) {
                            // ignore (just in case)
                        }
                    }
                    try {
                        mediaCodec.reset();
                    } catch (IllegalStateException e) {
                        Ln.w("Could not reset MediaCodec: " + e.getMessage());
                    }
                    if (surface != null) {
                        surface.release();
                    }
                }
            } while (alive);
        } finally {
            if (mediaCodec != null) {
                mediaCodec.release();
            }
            capture.release();
        }
    }

    private boolean prepareRetry(Size currentSize) {
        if (firstFrameSent) {
            ++consecutiveErrors;
            if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                // Definitively fail
                return false;
            }

            // Wait a bit to increase the probability that retrying will fix the problem
            // Progressive delay: wait longer on each retry to let rotation transition complete
            SystemClock.sleep(100L * consecutiveErrors);
            return true;
        }

        if (!downsizeOnError) {
            // Must fail immediately
            return false;
        }

        // Downsizing on error is only enabled if an encoding failure occurs before the first frame (downsizing later could be surprising)

        int newMaxSize = chooseMaxSizeFallback(currentSize);
        if (newMaxSize == 0) {
            // Must definitively fail
            return false;
        }

        boolean accepted = capture.setMaxSize(newMaxSize);
        if (!accepted) {
            return false;
        }

        // Retry with a smaller size
        Ln.i("Retrying with -m" + newMaxSize + "...");
        return true;
    }

    private static int chooseMaxSizeFallback(Size failedSize) {
        int currentMaxSize = Math.max(failedSize.getWidth(), failedSize.getHeight());
        for (int value : MAX_SIZE_FALLBACK) {
            if (value < currentMaxSize) {
                // We found a smaller value to reduce the video size
                return value;
            }
        }
        // No fallback, fail definitively
        return 0;
    }

    private void encode(MediaCodec codec, Streamer streamer) throws IOException {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

        boolean eos = false;
        do {
            int outputBufferId = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US);
            try {
                if (outputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // No output available within the timeout
                    if (stopped.get() || reset.hasPendingReset()) {
                        // Either stopped or a reset is pending but the EOS might not have been delivered
                        Ln.d("Exiting encode loop: stopped=" + stopped.get() + ", pendingReset=" + reset.hasPendingReset());
                        return;
                    }
                    continue;
                }
                eos = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                if (eos) {
                    // Do not write EOS buffer data to the stream: it may contain incomplete or
                    // corrupted NAL units (especially on Samsung Exynos encoders during rotation),
                    // which could cause the client to disconnect.
                    Ln.d("EOS received, exiting encode loop");
                    break;
                }
                if (outputBufferId >= 0 && bufferInfo.size > 0) {
                    ByteBuffer codecBuffer = codec.getOutputBuffer(outputBufferId);

                    boolean isConfig = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (!isConfig) {
                        // If this is not a config packet, then it contains a frame
                        firstFrameSent = true;
                        consecutiveErrors = 0;
                    }

                    streamer.writePacket(codecBuffer, bufferInfo);
                }
            } finally {
                if (outputBufferId >= 0) {
                    codec.releaseOutputBuffer(outputBufferId, false);
                }
            }
        } while (!eos);
    }

    private static MediaCodec createMediaCodec(Codec codec, String encoderName) throws IOException, ConfigurationException {
        if (encoderName != null) {
            Ln.d("Creating encoder by name: '" + encoderName + "'");
            try {
                MediaCodec mediaCodec = MediaCodec.createByCodecName(encoderName);
                String mimeType = Codec.getMimeType(mediaCodec);
                if (!codec.getMimeType().equals(mimeType)) {
                    Ln.e("Video encoder type for \"" + encoderName + "\" (" + mimeType + ") does not match codec type (" + codec.getMimeType() + ")");
                    throw new ConfigurationException("Incorrect encoder type: " + encoderName);
                }
                return mediaCodec;
            } catch (IllegalArgumentException e) {
                Ln.e("Video encoder '" + encoderName + "' for " + codec.getName() + " not found\n" + LogUtils.buildVideoEncoderListMessage());
                throw new ConfigurationException("Unknown encoder: " + encoderName);
            } catch (IOException e) {
                Ln.e("Could not create video encoder '" + encoderName + "' for " + codec.getName() + "\n" + LogUtils.buildVideoEncoderListMessage());
                throw e;
            }
        }

        try {
            MediaCodec mediaCodec = MediaCodec.createEncoderByType(codec.getMimeType());
            Ln.d("Using video encoder: '" + mediaCodec.getName() + "'");
            return mediaCodec;
        } catch (IOException | IllegalArgumentException e) {
            Ln.e("Could not create default video encoder for " + codec.getName() + "\n" + LogUtils.buildVideoEncoderListMessage());
            throw e;
        }
    }

    private static MediaFormat createFormat(String videoMimeType, int bitRate, float maxFps, List<CodecOption> codecOptions) {
        MediaFormat format = new MediaFormat();
        format.setString(MediaFormat.KEY_MIME, videoMimeType);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        // must be present to configure the encoder, but does not impact the actual frame rate, which is variable
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 60);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        if (Build.VERSION.SDK_INT >= AndroidVersions.API_24_ANDROID_7_0) {
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
        }
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, DEFAULT_I_FRAME_INTERVAL);
        // display the very first frame, and recover from bad quality when no new frames
        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_FRAME_DELAY_US); // µs
        if (maxFps > 0) {
            // The key existed privately before Android 10:
            // <https://android.googlesource.com/platform/frameworks/base/+/625f0aad9f7a259b6881006ad8710adce57d1384%5E%21/>
            // <https://github.com/Genymobile/scrcpy/issues/488#issuecomment-567321437>
            format.setFloat(KEY_MAX_FPS_TO_ENCODER, maxFps);
        }

        if (codecOptions != null) {
            for (CodecOption option : codecOptions) {
                String key = option.getKey();
                Object value = option.getValue();
                CodecUtils.setCodecOption(format, key, value);
                Ln.d("Video codec option set: " + key + " (" + value.getClass().getSimpleName() + ") = " + value);
            }
        }

        return format;
    }

    @Override
    public void start(TerminationListener listener) {
        thread = new Thread(() -> {
            // Some devices (Meizu) deadlock if the video encoding thread has no Looper
            // <https://github.com/Genymobile/scrcpy/issues/4143>
            Looper.prepare();

            try {
                streamCapture();
            } catch (ConfigurationException e) {
                // Do not print stack trace, a user-friendly error-message has already been logged
                Ln.d("Streaming stopped due to configuration error");
            } catch (IOException e) {
                // Broken pipe is expected on close, because the socket is closed by the client
                if (!IO.isBrokenPipe(e)) {
                    Ln.e("Video encoding error", e);
                } else {
                    Ln.d("Streaming stopped due to broken pipe (client disconnected)");
                }
            } catch (RuntimeException e) {
                Ln.e("Unexpected error during video encoding", e);
            } finally {
                Ln.d("Screen streaming stopped");
                listener.onTerminated(true);
            }
        }, "video");
        thread.start();
    }

    @Override
    public void stop() {
        if (thread != null) {
            stopped.set(true);
            reset.reset();
        }
    }

    @Override
    public void join() throws InterruptedException {
        if (thread != null) {
            thread.join();
        }
    }
}
