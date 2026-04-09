package com.genymobile.scrcpy.video;

import com.genymobile.scrcpy.util.Ln;

import android.media.MediaCodec;

import java.util.concurrent.atomic.AtomicBoolean;

public class CaptureReset implements SurfaceCapture.CaptureListener {

    private final AtomicBoolean reset = new AtomicBoolean();

    // Current instance of MediaCodec to "interrupt" on reset
    private MediaCodec runningMediaCodec;

    public boolean consumeReset() {
        return reset.getAndSet(false);
    }

    /**
     * Check if a reset is pending without consuming it.
     * <p/>
     * This is useful for the encode loop to detect a pending reset when the EOS signal might not have been delivered successfully
     * (for example if signalEndOfInputStream() threw an IllegalStateException).
     */
    public boolean hasPendingReset() {
        return reset.get();
    }

    public synchronized void reset() {
        reset.set(true);
        Ln.d("Capture reset requested");
        if (runningMediaCodec != null) {
            Ln.d("Signaling EOS to interrupt running MediaCodec");
            try {
                runningMediaCodec.signalEndOfInputStream();
            } catch (IllegalStateException e) {
                Ln.d("Could not signal EOS (MediaCodec not in running state): " + e.getMessage());
            }
        } else {
            Ln.d("No running MediaCodec to interrupt");
        }
    }

    public synchronized void setRunningMediaCodec(MediaCodec runningMediaCodec) {
        this.runningMediaCodec = runningMediaCodec;
    }

    @Override
    public void onInvalidated() {
        reset();
    }
}
