package com.github.tvbox.osc.music;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * 小贾影视仓 v27.13: 灵动条封面外的环形进度 —— 与 Blob 播放按钮分离(设计图场景一)。
 * 封面居中, 外圈一圈进度弧(雾蓝→雾紫), 弧头带光点; 进度由 BarBind 每 500ms 推进。
 */
public class CoverRingView extends View {
    private static final int C_A = 0xFF7AA8FF;
    private static final int C_B = 0xFFB07AFF;
    private static final int C_BG = 0x22FFFFFF;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float progress = 0f;
    private int gapPx;

    public CoverRingView(Context context) {
        this(context, null);
    }

    public CoverRingView(Context context, AttributeSet attrs) {
        super(context, attrs);
        bgPaint.setStyle(Paint.Style.STROKE);
        bgPaint.setColor(C_BG);
        bgPaint.setStrokeCap(Paint.Cap.ROUND);
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        headPaint.setStyle(Paint.Style.FILL);
        headPaint.setColor(0xFFFFFFFF);
        headGlow.setStyle(Paint.Style.FILL);
        gapPx = (int) (getResources().getDisplayMetrics().density * 8);
    }

    /** 留给内部封面的边距(px), 供布局取。 */
    public int coverInset() {
        return gapPx;
    }

    public void setProgress(float ratio) {
        progress = Math.max(0f, Math.min(1f, ratio));
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float stroke = Math.max(2.5f, Math.min(w, h) * 0.055f);
        bgPaint.setStrokeWidth(stroke);
        arcPaint.setStrokeWidth(stroke);
        arcPaint.setShader(new android.graphics.SweepGradient(w / 2f, h / 2f, C_A, C_B));
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float stroke = Math.max(2.5f, Math.min(cx, cy) * 0.055f);
        float r = Math.min(cx, cy) - stroke;
        c.drawCircle(cx, cy, r, bgPaint);
        if (progress > 0.002f) {
            float sweep = progress * 360f;
            c.drawArc(cx - r, cy - r, cx + r, cy + r, -90, sweep, false, arcPaint);
            double end = Math.toRadians(-90 + sweep);
            float hx = cx + (float) Math.cos(end) * r;
            float hy = cy + (float) Math.sin(end) * r;
            headGlow.setShader(new RadialGradient(hx, hy, stroke * 2.4f,
                    0xCCFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawCircle(hx, hy, stroke * 2.4f, headGlow);
            headGlow.setShader(null);
            c.drawCircle(hx, hy, stroke * 0.66f, headPaint);
        }
    }
}
