package com.aphexspin.chrome;

import android.content.Context;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/** The live picture behind the settings plate: the same renderer, one finger spins it here. */
public class PreviewView extends SurfaceView implements SurfaceHolder.Callback {
    private final Runner runner;
    private boolean resumed, ready;
    private float lastX, lastY;
    private long lastT;
    private final double[] vel = new double[2];

    public PreviewView(Context c) {
        super(c);
        getHolder().addCallback(this);
        runner = new Runner(c, getHolder(), false);
    }

    void resume() {
        resumed = true;
        if (ready) runner.start();
    }

    void pause() {
        resumed = false;
        runner.stop();
    }

    void release() {
        runner.release();
    }

    @Override
    public void surfaceCreated(SurfaceHolder h) {
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int format, int w, int hh) {
        runner.surface(w, hh);
        ready = true;
        if (resumed) runner.start();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        ready = false;
        runner.surfaceGone();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
        float x = e.getX(), y = e.getY();
        long t = e.getEventTime();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                lastX = x;
                lastY = y;
                lastT = t;
                vel[0] = vel[1] = 0;
                runner.touchStart();
                return true;
            case MotionEvent.ACTION_MOVE: {
                double k = 3.2 / w;
                double ax = (y - lastY) * k, ay = (x - lastX) * k;
                double et = Math.max(1 / 120.0, (t - lastT) / 1000.0);
                vel[0] = 0.55 * vel[0] + 0.45 * ax / et;
                vel[1] = 0.55 * vel[1] + 0.45 * ay / et;
                runner.dragBy(ax, ay, x / w * 2.0 - 1.0, 1.0 - y / h * 2.0);
                lastX = x;
                lastY = y;
                lastT = t;
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                runner.touchEnd(t - lastT < 80 ? new double[]{vel[0], vel[1]} : null);
                return true;
            default:
                return true;
        }
    }
}
