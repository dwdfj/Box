package com.github.tvbox.osc.music;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * 小贾影视仓 v27.13: Blob 液态播放按钮(Voyage 式) —— 按 v27.12 用户反馈重做:
 * 1) 进度环**不再和 blob 挤在一起**: 环在最外圈, 中间留出间隙, blob 在内圈独立游动;
 * 2) blob 增加径向渐变高光 + 边缘提亮, 看起来是"一坨会流动的水", 不是纯色圆;
 * 3) 游动由三层正弦谐波叠加(3/5/7 阶), 播放时快、暂停时慢呼吸;
 * 4) 进度环带发光头, 随歌曲进度递增(这就是用户问的"环绕播放按键的特效")。
 *
 * v27.16 新增「液态波浪环」: 在进度环和 blob 之间再套一圈不规则闭合波环 ——
 * 就是老大发的参考图里那个「外沿有起伏、粗细不均匀、不是规整圆」的环。
 * 它不带进度信息, 只负责律动; 暂停时波形收敛到几乎成一根细线(参考图静止状态)。
 * 层次: 最外层进度环(信息) → 液态波浪环(律动) → blob 本体 + 中心符号(手感)。
 */
public class BlobPlayButton extends View {
    private static final int C_A = 0xFF7AA8FF; // 雾蓝
    private static final int C_B = 0xFFB07AFF; // 雾紫
    private static final int C_RING_BG = 0x33FFFFFF;

    private final Paint blobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** v27.16: 液态波浪环。 */
    private final Paint waveRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path blob = new Path();
    private final Path waveRing = new Path();
    private final RectF ringRect = new RectF();

    private final ValueAnimator wave;
    private float phase = 0f;
    private boolean playing = false;
    private float progress = 0f;
    private long lastFrame;

    public BlobPlayButton(Context context) {
        this(context, null);
    }

    public BlobPlayButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        ringBgPaint.setStyle(Paint.Style.STROKE);
        ringBgPaint.setColor(C_RING_BG);
        ringBgPaint.setStrokeCap(Paint.Cap.ROUND);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeCap(Paint.Cap.ROUND);
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setColor(0x33FFFFFF);
        headGlow.setStyle(Paint.Style.FILL);
        headGlow.setColor(0x66FFFFFF);
        glyphPaint.setColor(0xFFFFFFFF);
        waveRingPaint.setStyle(Paint.Style.STROKE);
        waveRingPaint.setColor(0x667AA8FF);
        waveRingPaint.setStrokeCap(Paint.Cap.ROUND);
        wave = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
        wave.setDuration(9000);
        wave.setRepeatCount(ValueAnimator.INFINITE);
        wave.setInterpolator(new LinearInterpolator());
        wave.addUpdateListener(a -> {
            phase = (float) a.getAnimatedValue();
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
        float min = Math.min(w, h);
        float ringW = Math.max(3f, min * 0.032f);
        ringBgPaint.setStrokeWidth(ringW);
        ringPaint.setStrokeWidth(ringW);
        rimPaint.setStrokeWidth(Math.max(1f, min * 0.008f));
        waveRingPaint.setStrokeWidth(Math.max(2f, min * 0.014f));
        ringPaint.setShader(new android.graphics.SweepGradient(
                w / 2f, h / 2f, C_A, C_B));
        // blob 主体: 斜向渐变
        blobPaint.setShader(new LinearGradient(0, 0, w, h, C_A, C_B, Shader.TileMode.CLAMP));
        // 环形进度矩形
        float inset = ringW / 2f;
        ringRect.set(inset, inset, w - inset, h - inset);
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float min = Math.min(cx, cy);
        float ringR = min * 0.94f;
        // --- 外圈进度环 (最外层) ---
        float ringW = Math.max(3f, min * 2 * 0.032f);
        c.drawArc(cx - ringR + ringW / 2f, cy - ringR + ringW / 2f,
                cx + ringR - ringW / 2f, cy + ringR - ringW / 2f, -90, 360, false, ringBgPaint);
        if (progress > 0.002f) {
            float sweep = progress * 360f;
            c.drawArc(cx - ringR + ringW / 2f, cy - ringR + ringW / 2f,
                    cx + ringR - ringW / 2f, cy + ringR - ringW / 2f, -90, sweep, false, ringPaint);
            // 进度头: 小光点
            double endAngle = Math.toRadians(-90 + sweep);
            float hx = cx + (float) Math.cos(endAngle) * (ringR - ringW / 2f);
            float hy = cy + (float) Math.sin(endAngle) * (ringR - ringW / 2f);
            headGlow.setShader(new RadialGradient(hx, hy, ringW * 2.2f,
                    0xBBFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawCircle(hx, hy, ringW * 2.2f, headGlow);
            headGlow.setShader(null);
            c.drawCircle(hx, hy, ringW * 0.62f, glyphPaint);
        }
        // --- v27.16 液态波浪环 (进度环与 blob 之间) ---
        // 参考图那个「外沿起伏、粗细不均」的闭合波环: 用不同阶数的谐波叠出
        // 内外不规则的环带, 靠 path 描边形成宽度变化。暂停时振幅收敛到 0.004(几乎成细线)。
        float wR = ringR * 0.885f;
        float amp = playing ? 1f : 0.10f; // 播放时明显、暂停时收敛
        waveRing.reset();
        int M = 120;
        for (int i = 0; i <= M; i++) {
            double th = i * Math.PI * 2 / M;
            float wob = 1f
                    + amp * (0.030f * (float) Math.sin(4 * th + 1.6f * phase)
                    + 0.019f * (float) Math.sin(6 * th - 2.1f * phase)
                    + 0.011f * (float) Math.sin(9 * th + 0.9f * phase));
            float rr = wR * wob;
            float x = cx + (float) Math.cos(th) * rr;
            float y = cy + (float) Math.sin(th) * rr;
            if (i == 0) waveRing.moveTo(x, y);
            else waveRing.lineTo(x, y);
        }
        waveRing.close();
        waveRingPaint.setAlpha(playing ? 0x99 : 0x40);
        c.drawPath(waveRing, waveRingPaint);

        // --- blob 本体 (内圈, 与环留白) ---
        float R = ringR * 0.74f;
        blob.reset();
        int N = 80;
        for (int i = 0; i <= N; i++) {
            double th = i * Math.PI * 2 / N;
            float wob = 1f
                    + 0.038f * (float) Math.sin(3 * th + 2.0f * phase)
                    + 0.026f * (float) Math.sin(5 * th - 1.3f * phase)
                    + 0.016f * (float) Math.sin(7 * th + 0.7f * phase)
                    // 播放时再叠一层高频细纹, 静止时几乎看不出
                    + (playing ? 0.008f * (float) Math.sin(11 * th - 2.6f * phase) : 0f);
            float rr = R * wob;
            float x = cx + (float) Math.cos(th) * rr;
            float y = cy + (float) Math.sin(th) * rr;
            if (i == 0) blob.moveTo(x, y);
            else blob.lineTo(x, y);
        }
        blob.close();
        c.drawPath(blob, blobPaint);
        c.drawPath(blob, rimPaint);
        // --- 中心 ▶/⏸ ---
        float g = R * 0.40f;
        if (playing) {
            float bar = g * 0.30f, gap = g * 0.24f;
            c.drawRoundRect(cx - gap - bar, cy - g, cx - gap, cy + g, bar / 2f, bar / 2f, glyphPaint);
            c.drawRoundRect(cx + gap, cy - g, cx + gap + bar, cy + g, bar / 2f, bar / 2f, glyphPaint);
        } else {
            Path tri = new Path();
            tri.moveTo(cx - g * 0.52f, cy - g);
            tri.lineTo(cx + g * 0.78f, cy);
            tri.lineTo(cx - g * 0.52f, cy + g);
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
