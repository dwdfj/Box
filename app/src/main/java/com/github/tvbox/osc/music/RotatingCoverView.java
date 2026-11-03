package com.github.tvbox.osc.music;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * 小贾影视仓 v27.15: 圆形旋转唱片封面(全屏播放页主视觉)。
 *
 * 结构(从外到内):
 *   ① 黑胶纹圈: 两圈细弧 + 一圈淡环, 跟着封面一起慢转;
 *   ② 封面本体: BitmapShader 裁成正圆, 播放时 360°/20s 匀速旋转, 暂停停在当前角度;
 *   ③ 中心轴孔: 深色小圆 + 高光, 做出黑胶唱片质感;
 *   ④ 外圈光晕: 播放时随角度流动的柔光, 暂停时渐暗。
 *
 * 用法: setBitmap() 换封面, setPlaying() 控制转/停, 无需外部动画。
 */
public class RotatingCoverView extends View {
    private static final long PERIOD_MS = 20000L; // 一圈 20 秒, 接近真唱片偏慢的观感

    private final Paint coverPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint discPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint holePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();

    private Bitmap bitmap;
    private BitmapShader shader;
    private final ValueAnimator spin;
    private float angle = 0f;
    private boolean playing = false;
    private float playFade = 0f;

    public RotatingCoverView(Context context) {
        this(context, null);
    }

    public RotatingCoverView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        discPaint.setStyle(Paint.Style.FILL);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setColor(0x22FFFFFF);
        holePaint.setStyle(Paint.Style.FILL);
        holePaint.setColor(0xFF10141E);
        glowPaint.setStyle(Paint.Style.FILL);
        spin = ValueAnimator.ofFloat(0f, 1f);
        spin.setDuration(PERIOD_MS);
        spin.setRepeatCount(ValueAnimator.INFINITE);
        spin.setInterpolator(new LinearInterpolator());
        spin.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            angle = f * 360f;
            playFade += ((playing ? 1f : 0f) - playFade) * 0.08f;
            invalidate();
        });
        spin.start();
    }

    public void setBitmap(Bitmap b) {
        bitmap = b;
        if (b != null) {
            shader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            coverPaint.setShader(shader);
        } else {
            shader = null;
            coverPaint.setShader(null);
        }
        invalidate();
    }

    public void setPlaying(boolean p) {
        playing = p;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float min = Math.min(w, h);
        ringPaint.setStrokeWidth(Math.max(1f, min * 0.006f));
        // 黑胶底盘: 比封面大一圈, 径向由深到更深的渐变
        discPaint.setShader(new RadialGradient(w / 2f, h / 2f, min * 0.5f,
                0xFF1A1F2B, 0xFF0B0E15, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float min = Math.min(cx, cy);

        // ① 黑胶底盘
        c.drawCircle(cx, cy, min * 0.995f, discPaint);

        // ② 播放时流动的外圈柔光
        if (playFade > 0.02f) {
            float hx = cx + (float) Math.cos(Math.toRadians(angle - 90)) * min * 0.86f;
            float hy = cy + (float) Math.sin(Math.toRadians(angle - 90)) * min * 0.86f;
            glowPaint.setShader(new RadialGradient(hx, hy, min * 0.42f,
                    0x664E8BFF, 0x004E8BFF, Shader.TileMode.CLAMP));
            glowPaint.setAlpha((int) (255 * playFade));
            c.drawCircle(hx, hy, min * 0.42f, glowPaint);
            glowPaint.setShader(null);
            glowPaint.setAlpha(255);
        }

        // ③ 封面正圆(随角度转)
        // v27.16 修: 原来按 min(bmpW,bmpH) 算缩放, 非正方形封面会「短边撑满、长边溢出」,
        // 再被圆裁一刀后只剩中间一小块 —— 用户看到的就是「封面没铺满圆」。
        // 改成 cover 模式: 取较长边为基准, 保证圆内每一处都被图覆盖, 只裁掉溢出的部分。
        float coverR = min * 0.995f; // 铺满到黑胶底盘边缘, 外圈只留极细的黑胶描边
        if (shader != null && bitmap != null) {
            float bw = bitmap.getWidth(), bh = bitmap.getHeight();
            if (bw > 0 && bh > 0) {
                float scale = (coverR * 2f) / Math.min(bw, bh); // 短边刚好铺满圆直径
                matrix.setScale(scale, scale);
                // 把图中心对到圆心, 再绕圆心旋转
                matrix.postTranslate(cx - bw * scale / 2f, cy - bh * scale / 2f);
                matrix.postRotate(angle, cx, cy);
                shader.setLocalMatrix(matrix);
                c.drawCircle(cx, cy, coverR, coverPaint);
            }
        } else {
            discPaint.setShader(null);
            discPaint.setColor(0xFF232936);
            c.drawCircle(cx, cy, coverR, discPaint);
            discPaint.setShader(new RadialGradient(cx, cy, min * 0.5f,
                    0xFF1A1F2B, 0xFF0B0E15, Shader.TileMode.CLAMP));
        }

        // ④ 黑胶纹圈: 叠在封面之上, 做出唱片的纹路感(只在边缘留两圈, 不挡中心)
        c.save();
        c.rotate(angle, cx, cy);
        ringPaint.setColor(0x1A000000);
        c.drawCircle(cx, cy, min * 0.90f, ringPaint);
        c.drawCircle(cx, cy, min * 0.955f, ringPaint);
        ringPaint.setColor(0x22FFFFFF);
        c.drawCircle(cx, cy, min * 0.90f, ringPaint);
        c.drawCircle(cx, cy, min * 0.955f, ringPaint);
        c.restore();

        // ⑤ 中心轴孔(v27.16: 缩小, 别在圆形封面上挖太大一块)
        c.drawCircle(cx, cy, min * 0.055f, holePaint);
        Paint highlight = new Paint(Paint.ANTI_ALIAS_FLAG);
        highlight.setColor(0x33FFFFFF);
        c.drawCircle(cx, cy - min * 0.010f, min * 0.020f, highlight);
    }
}
