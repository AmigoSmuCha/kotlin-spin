package com.cornholio.ncs;

import android.media.audiofx.Visualizer;

/**
 * Hears what the phone plays: Android's Visualizer on the output mix, read
 * every ten milliseconds, its overlapping snapshots stitched back into one
 * stream (Stitch) and fed to the tap at 16 kHz, as parec feeds cornholio.
 * When nothing has played for two seconds it looks only twenty times a
 * second. It reads the sound's samples and keeps none of them.
 */
final class Listener implements Runnable {
    private final AudioTap tap;
    private final AudioTap.Clock clock;
    private final boolean evened;
    private Visualizer vis;
    private volatile Thread thread;
    private volatile boolean running;
    /** Why it could not start, or null. */
    String error;

    Listener(AudioTap tap, AudioTap.Clock clock, boolean evened) {
        this.tap = tap;
        this.clock = clock;
        this.evened = evened;
    }

    boolean start() {
        try {
            vis = new Visualizer(0);
            vis.setEnabled(false);
            int[] range = Visualizer.getCaptureSizeRange();
            vis.setCaptureSize(Math.min(1024, range[1]));
            vis.setScalingMode(evened ? Visualizer.SCALING_MODE_NORMALIZED : Visualizer.SCALING_MODE_AS_PLAYED);
            vis.setEnabled(true);
        } catch (RuntimeException e) {
            error = "can't hear the music · the phone won't share its sound";
            releaseVis();
            return false;
        }
        running = true;
        thread = new Thread(this, "ncs-listen");
        thread.setPriority(Thread.NORM_PRIORITY);
        thread.start();
        return true;
    }

    void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            try {
                t.join(300);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        releaseVis();
    }

    private void releaseVis() {
        Visualizer v = vis;
        vis = null;
        if (v != null) {
            try {
                v.setEnabled(false);
            } catch (RuntimeException ignored) {
            }
            try {
                v.release();
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Override
    public void run() {
        final Thread me = Thread.currentThread();
        Visualizer v = vis;
        if (v == null) return;
        int size, rate;
        try {
            size = v.getCaptureSize();
            rate = v.getSamplingRate() / 1000;
        } catch (RuntimeException e) {
            return;
        }
        if (rate <= 0) rate = 48000;
        Stitch st = new Stitch(size, rate);
        byte[] buf = new byte[size];
        long quietSince = -1;
        while (running && thread == me) {
            long t = System.nanoTime();
            int status;
            try {
                status = v.getWaveForm(buf);
            } catch (RuntimeException e) {
                break;
            }
            boolean quiet = true;
            if (status == Visualizer.SUCCESS) {
                final double now = clock.now();
                st.snapshot(buf, t, (s, c) -> tap.feed(s, c, now));
                for (byte b : buf) if (b != (byte) 0x80) { quiet = false; break; }
            }
            if (!quiet) quietSince = -1;
            else if (quietSince < 0) quietSince = t;
            // nothing playing for two seconds: look twenty times a second, not a hundred
            long every = quietSince >= 0 && t - quietSince > 2_000_000_000L ? 50 : 10;
            long spent = (System.nanoTime() - t) / 1_000_000L;
            try {
                Thread.sleep(Math.max(2, every - spent));
            } catch (InterruptedException e) {
                break;
            }
        }
    }
}
