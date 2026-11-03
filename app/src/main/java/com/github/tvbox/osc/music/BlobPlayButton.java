package com.github.tvbox.osc.music;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * 小贾影视仓 v27.12: Blob 液态播放按钮(Voyage 式)。
 * 按钮轮廓用多层正弦谐波实时扰动成"水滴/果冻"形状, 播放时持续游动;
 * 外圈是环形进度, 随歌曲进度递增。中心绘制 ▶/⏸。
 */
public class BlobPlayButton extends View {
    private static final int C_A = 0xFF7AA8FF; // 雾蓝
    private static final int C_B = 0xFFB07AFF; // 雾紫
    private static final int C_RING_BG = 0x33FFFFFF;
    private static final int C_FG = 0xFFFFFFFF;

    private final Paint blobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path blob = new Path();

    private final ValueAnimator wave;
    private float phase = 0f;      // 游动相位
    private boolean playing = false;
    private float progress = 0f;   // 0~1
    private long lastFrame;

    public BlobPlayButton(Context context) {
        this(context, null);
    }

    public BlobPlayButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        ringBgPaint.setStyle(Paint.Style.STROKE);
        ringBgPaint.setColor(C_RING_BG);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setColor(C_FG);
        ringPaint.setStrokeCap(Paint.Cap.ROUND);
        glyphPaint.setColor(Color.WHITE);
        wave = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
        wave.setDuration(9000);
        wave.setRepeatCount(ValueAnimator.INFINITE);
        wave.setInterpolator(new LinearInterpolator());
        wave.addUpdateListener(a -> {
            phase = (float) a.getAnimatedValue();
            // 播放时游动快, 暂停时缓慢呼吸
            long now = System.currentTimeMillis();
            if (now - lastFrame < (playing ? 16 : 48)) return;
            lastFrame = now;
            invalidate();
        });
        wave.start();
    }

    public void setPlaying(boolean p) {
        playing = p;
        invalidate();
    }

    public void setProgress(float ratio) {
        progress = Math.max(0f, Math.min(1f, ratio));
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float ringW = Math.max(3f, w * 0.035f);
        ringBgPaint.setStrokeWidth(ringW);
        ringPaint.setStrokeWidth(ringW);
        blobPaint.setShader(new LinearGradient(0, 0, w, h, C_A, C_B, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float ringR = Math.min(cx, cy) * 0.94f;
        float R = ringR * 0.82f;
        // 环形进度
        c.drawArc(cx - ringR, cy - ringR, cx + ringR, cy + ringR, -90, 360, false, ringBgPaint);
        if (progress > 0.001f) {
            c.drawArc(cx - ringR, cy - ringR, cx + ringR, cy + ringR, -90, progress * 360, false, ringPaint);
        }
        // blob 轮廓: 极坐标谐波扰动
        blob.reset();
        int N = 72;
        for (int i = 0; i <= N; i++) {
            double th = i * Math.PI * 2 / N;
            float wob = 1f
                    + 0.035f * (float) Math.sin(3 * th + 2 * phase)
                    + 0.025f * (float) Math.sin(5 * th - 1.3 * phase)
                    + 0.015f * (float) Math.sin(7 * th + 0.7 * phase);
            float x = cx + (float) Math.cos(th) * R * wob;
            float y = cy + (float) Math.sin(th) * R * wob;
            if (i == 0) blob.moveTo(x, y);
            else blob.lineTo(x, y);
        }
        blob.close();
        c.drawPath(blob, blobPaint);
        // 中心 ▶/⏸
        float g = R * 0.42f;
        if (playing) {
            float bar = g * 0.30f, gap = g * 0.22f;
            c.drawRoundRect(cx - gap - bar, cy - g, cx - gap, cy + g, bar / 2, bar / 2, glyphPaint);
            c.drawRoundRect(cx + gap, cy - g, cx + gap + bar, cy + g, bar / 2, bar / 2, glyphPaint);
        } else {
            Path tri = new Path();
            tri.moveTo(cx - g * 0.55f, cy - g);
            tri.lineTo(cx + g * 0.75f, cy);
            tri.lineTo(cx - g * 0.55f, cy + g);
            tri.close();
            c.drawPath(tri, glyphPaint);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        wave.cancel();
    }
}
