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
