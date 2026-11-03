package com.github.tvbox.osc.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * 小贾影视仓 v27.30: 播放加载动画(替代系统转圈)。
 *
 * <p>5 根律动条, 相位依次错开, 整体像"声波在跑", 取主题紫 #8B6BFF。
 * 只用一个 ValueAnimator 驱动重绘, 没有多余 View 层级, 对起播零负担。
 */
public class LoadingAnimView extends View {

    private static final int BAR_COUNT = 5;
    private static final int COLOR_MAIN = 0xFF8B6BFF;
    private static final int COLOR_TAIL = 0xFF5A46C8;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private ValueAnimator mAnimator;
    private float mPhase = 0f;

    public LoadingAnimView(Context context) {
        this(context, null);
    }

    public LoadingAnimView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public LoadingAnimView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mPaint.setStyle(Paint.Style.FILL);
    }

    private void ensureAnimator() {
        if (mAnimator != null) return;
        mAnimator = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
        mAnimator.setDuration(1100);
        mAnimator.setRepeatCount(ValueAnimator.INFINITE);
        mAnimator.setInterpolator(new LinearInterpolator());
        mAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                mPhase = (float) animation.getAnimatedValue();
                invalidate();
            }
        });
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ensureAnimator();
        if (!mAnimator.isStarted()) mAnimator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mAnimator != null) mAnimator.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (mAnimator == null) return;
        if (visibility == VISIBLE) {
            if (!mAnimator.isStarted()) mAnimator.start();
        } else {
            mAnimator.cancel();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float slot = (float) w / BAR_COUNT;
        float barW = slot * 0.52f;
        float radius = barW / 2f;
        float minH = h * 0.24f;
        float maxH = h;

        for (int i = 0; i < BAR_COUNT; i++) {
            float wave = (float) Math.sin(mPhase + i * 0.78f);   // -1..1
            float factor = 0.5f + 0.5f * wave;                   // 0..1
            float barH = minH + (maxH - minH) * factor;
            float left = i * slot + (slot - barW) / 2f;
            float top = (h - barH) / 2f;
            mRect.set(left, top, left + barW, top + barH);
            // 中间的条最亮, 两侧渐暗, 让"波"有方向感
            float dist = Math.abs(i - (BAR_COUNT - 1) / 2f) / ((BAR_COUNT - 1) / 2f);
            mPaint.setColor(blend(COLOR_MAIN, COLOR_TAIL, dist * 0.75f));
            mPaint.setAlpha((int) (255 * (0.55f + 0.45f * factor)));
            canvas.drawRoundRect(mRect, radius, radius, mPaint);
        }
    }

    private static int blend(int from, int to, float t) {
        float f = Math.max(0f, Math.min(1f, t));
        int a = (int) (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * f);
        int r = (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * f);
        int g = (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * f);
        int b = (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f);
        return Color.argb(a, r, g, b);
    }
}
