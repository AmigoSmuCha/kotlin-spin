package com.cornholio.ncs;

import android.content.Context;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/** The live picture in settings: the same renderer playing the demo song; any finger paints the letters here. */
public class PreviewView extends SurfaceView implements SurfaceHolder.Callback {
    private final Runner runner;
    private boolean resumed, ready;

    public PreviewView(Context c) {
        super(c);
        getHolder().addCallback(this);
        runner = new Runner(c, getHolder(), false, true);
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
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                runner.paintStart(e.getPointerId(0), e.getX(0), e.getY(0));
                return true;
            case MotionEvent.ACTION_POINTER_DOWN: {
                int i = e.getActionIndex();
                runner.paintStart(e.getPointerId(i), e.getX(i), e.getY(i));
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) runner.paint(e.getPointerId(i), e.getX(i), e.getY(i));
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                runner.paintEnd(e.getPointerId(e.getActionIndex()));
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                runner.paintEnd(-1);
                return true;
            default:
                return true;
        }
    }
}
