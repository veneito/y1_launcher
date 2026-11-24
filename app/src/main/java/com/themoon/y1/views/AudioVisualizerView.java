package com.themoon.y1.views;

import android.content.Context;
import android.view.View;

public class AudioVisualizerView extends View {
    public static final int STYLE_BARS = 0, STYLE_WAVE = 1;

    private byte[] fftData;
    private float[] currentHeights; // 🚀 부드러운 움직임을 위한 이전 높이 기억 장치
    private final float[] peaks;    // fork: falling peak caps
    private final int[] peakHold;   // frames a peak stays put before it starts to fall
    private android.graphics.Paint paint;
    private final android.graphics.Paint glowPaint;
    private final android.graphics.Path wavePath = new android.graphics.Path(); // reused: no allocation per frame
    private int barCount = 40; // 🚀 막대기 개수를 늘려서 옆으로 쫙 퍼지게!
    private int style = STYLE_BARS;
    private long lastDataMs;
    private boolean animating;

    private static final int FRAME_MS = 33;      // fork: 30 fps (was 16 ms / 60 fps) to spare the Y1's CPU
    private static final long IDLE_AFTER_MS = 600; // no FFT data this long (paused) -> stop redrawing once bars settle
    private static final int PEAK_HOLD_FRAMES = 12;
    private static final float PEAK_FALL = 1.5f;   // px per frame once the hold ends

    public AudioVisualizerView(Context context) {
        super(context);
        paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(android.graphics.Paint.Style.FILL);
        paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        glowPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        glowPaint.setStyle(android.graphics.Paint.Style.STROKE);
        glowPaint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        glowPaint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        currentHeights = new float[barCount];
        peaks = new float[barCount];
        peakHold = new int[barCount];
    }

    public void setStyle(int style) {
        this.style = style;
        invalidate();
    }

    public void updateVisualizer(byte[] fft, int color) {
        this.fftData = fft;
        paint.setColor(color);
        glowPaint.setColor(color);
        lastDataMs = android.os.SystemClock.uptimeMillis();
        if (!animating && getVisibility() == View.VISIBLE) {
            animating = true;
            postInvalidate(); // restart the draw loop after an idle stop (FFT arrives on a worker thread)
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == View.VISIBLE) invalidate(); // resume the loop when the player shows it again
    }

    @Override
    protected void onDraw(android.graphics.Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();
        float barWidth = width / (float) barCount;
        boolean receiving = android.os.SystemClock.uptimeMillis() - lastDataMs < IDLE_AFTER_MS;

        float moving = 0;
        for (int i = 0; i < barCount; i++) {
            float targetHeight = 0;
            if (receiving && fftData != null && (i * 2 + 3) < fftData.length) {
                byte rfk = fftData[i * 2 + 2];
                byte ifk = fftData[i * 2 + 3];
                float magnitude = (float) Math.hypot(rfk, ifk);
                // 🚀 1. 높이 제한: 아무리 소리가 커도 화면 높이의 85%를 넘지 못하게 캡을 씌웁니다.
                targetHeight = Math.min(height * 0.85f, (magnitude * height) / 100f);
            }
            // 🚀 2. 부드러운 보간: 목표 지점까지 한 번에 점프하지 않고 15%씩 스무스하게 따라갑니다.
            currentHeights[i] += (targetHeight - currentHeights[i]) * 0.15f;

            if (currentHeights[i] >= peaks[i]) {
                peaks[i] = currentHeights[i];
                peakHold[i] = PEAK_HOLD_FRAMES;
            } else if (peakHold[i] > 0) {
                peakHold[i]--;
            } else {
                peaks[i] = Math.max(currentHeights[i], peaks[i] - PEAK_FALL);
            }
            moving += currentHeights[i] + peaks[i];
        }

        if (style == STYLE_WAVE) {
            drawWave(canvas, width, height, barWidth);
        } else {
            paint.setStrokeWidth(barWidth * 0.4f); // 🚀 막대기 두께를 얇고 세련되게 (40%)
            float capH = Math.max(2f, barWidth * 0.15f);
            for (int i = 0; i < barCount; i++) {
                float x = i * barWidth + (barWidth / 2f);
                canvas.drawLine(x, height, x, height - currentHeights[i], paint);
                if (peaks[i] > 1f) {
                    float y = height - peaks[i] - capH * 2;
                    canvas.drawRect(x - barWidth * 0.2f, y, x + barWidth * 0.2f, y + capH, paint);
                }
            }
        }

        // 🚀 3. 화면에 보일 때만, fork: 30 fps, and stop once paused and settled
        animating = getVisibility() == View.VISIBLE && (receiving || moving > 1f);
        if (animating) {
            postInvalidateDelayed(FRAME_MS);
        }
    }

    // XMB-style: a glowing line through the bar heights, mirrored around the middle like the PSP ribbon
    private void drawWave(android.graphics.Canvas canvas, int width, int height, float barWidth) {
        float mid = height * 0.6f;
        wavePath.reset();
        wavePath.moveTo(0, mid);
        float px = 0, py = mid;
        for (int i = 0; i < barCount; i++) {
            float x = i * barWidth + barWidth / 2f;
            float y = mid - currentHeights[i] * 0.55f * (i % 2 == 0 ? 1 : -1);
            wavePath.quadTo(px, py, (px + x) / 2f, (py + y) / 2f); // midpoint smoothing
            px = x;
            py = y;
        }
        wavePath.quadTo(px, py, width, mid);

        int a = glowPaint.getAlpha();
        glowPaint.setStrokeWidth(barWidth * 1.2f);
        glowPaint.setAlpha(60);                   // soft glow
        canvas.drawPath(wavePath, glowPaint);
        glowPaint.setStrokeWidth(Math.max(2f, barWidth * 0.18f));
        glowPaint.setAlpha(230);                  // bright core line
        canvas.drawPath(wavePath, glowPaint);
        glowPaint.setAlpha(a);
    }
}
