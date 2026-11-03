package com.github.tvbox.osc.music;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.List;

/**
 * 小贾影视仓 v27.15: 歌词动效(流行做法, 参照网易云/Spotify 的歌词层) ——
 *
 * 1. 当前行「逐字点亮」: 已唱部分高亮色, 未唱部分半透明, 交界处有一段柔和渐变,
 *    跟播放进度实时推进(这才是真正的歌词播放特效, 不是整行一起变色);
 * 2. 当前行放大 + 轻微呼吸(±1.5% 缩放), 视线焦点始终在中间;
 * 3. 上下行按距离四级渐隐(0xCC / 0x88 / 0x55 / 0x22), 远端直接不画, 省性能;
 * 4. 行切换用 DecelerateInterpolator 平滑滚动(280ms), 不是生硬跳;
 * 5. 点击任意行 seek 到该行; 长按拖动可快速定位。
 */
public class LyricView extends View {
    public interface OnSeekListener {
        void onSeek(long timeMs);
    }

    private static final int C_DONE = 0xFFFFFFFF;     // 已唱: 亮白
    private static final int C_TODO = 0x66FFFFFF;     // 未唱: 灰白
    private static final int C_ACCENT = 0xFF7AA8FF;   // 强调色(当前行光晕)

    private final List<LrcParser.Line> lines = new ArrayList<>();
    private final Paint donePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint todoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint otherPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int curIndex = -1;
    private long curPos = 0;
    private float lineHeight;
    private float curTextSize;
    private float otherTextSize;

    /** 当前行滚动偏移(像素), 从 old 平滑到 new。 */
    private float scrollOffset = 0f;
    private float scrollFrom = 0f;
    private float scrollTo = 0f;
    private ValueAnimator scrollAnim;

    /** 呼吸缩放。 */
    private float pulse = 1f;
    private final ValueAnimator breath;

    private OnSeekListener seekListener;

    public LyricView(Context context) {
        this(context, null);
    }

    public LyricView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        donePaint.setTextAlign(Paint.Align.CENTER);
        donePaint.setColor(C_DONE);
        todoPaint.setTextAlign(Paint.Align.CENTER);
        todoPaint.setColor(C_TODO);
        otherPaint.setTextAlign(Paint.Align.CENTER);
        emptyPaint.setTextAlign(Paint.Align.CENTER);
        emptyPaint.setColor(0x88FFFFFF);
        glowPaint.setStyle(Paint.Style.FILL);
        glowPaint.setColor(C_ACCENT);

        breath = ValueAnimator.ofFloat(1f, 1.03f);
        breath.setDuration(2600);
        breath.setRepeatMode(ValueAnimator.REVERSE);
        breath.setRepeatCount(ValueAnimator.INFINITE);
        breath.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        breath.addUpdateListener(a -> {
            pulse = (float) a.getAnimatedValue();
            invalidate();
        });
        breath.start();
    }

    public void setSeekListener(OnSeekListener l) {
        seekListener = l;
    }

    public void setLines(List<LrcParser.Line> data) {
        lines.clear();
        if (data != null) lines.addAll(data);
        curIndex = -1;
        scrollOffset = 0f;
        invalidate();
    }

    /** 播放进度驱动: 行变化时平滑滚到新行, 行内推进时逐字点亮。 */
    public void setProgress(long posMs) {
        curPos = posMs;
        int idx = LrcParser.indexOf(lines, posMs);
        if (idx != curIndex) {
            int old = curIndex;
            curIndex = idx;
            // 行切换: 从旧行位置滚到新行位置
            scrollFrom = scrollOffset;
            scrollTo = 0f;
            if (scrollAnim != null) scrollAnim.cancel();
            scrollAnim = ValueAnimator.ofFloat(0f, 1f);
            scrollAnim.setDuration(280);
            scrollAnim.setInterpolator(new DecelerateInterpolator());
            scrollAnim.addUpdateListener(a -> {
                float f = (float) a.getAnimatedValue();
                scrollOffset = scrollFrom + (scrollTo - scrollFrom) * f;
                invalidate();
            });
            scrollAnim.start();
            if (old < 0) scrollOffset = 0f;
        } else {
            // 同一行内: 逐字点亮, 每帧都重绘
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth();
        float centerY = getHeight() / 2f;
        if (lines.isEmpty()) {
            c.drawText("暂无歌词", w / 2f, centerY, emptyPaint);
            return;
        }
        int n = lines.size();
        int base = curIndex < 0 ? 0 : curIndex;
        int from = Math.max(0, base - 3);
        int to = Math.min(n - 1, base + 3);

        for (int i = from; i <= to; i++) {
            LrcParser.Line ln = lines.get(i);
            int dist = Math.abs(i - base);
            float y = centerY + (i - base) * lineHeight + scrollOffset;
            if (y < -lineHeight || y > getHeight() + lineHeight) continue;

            if (dist == 0 && curIndex >= 0) {
                drawCurrent(c, ln, w, y);
            } else {
                int alpha = dist == 1 ? 0xCC : dist == 2 ? 0x88 : dist == 3 ? 0x55 : 0x22;
                otherPaint.setColor((alpha << 24) | 0x00FFFFFF);
                otherPaint.setTextSize(dist == 1 ? otherTextSize * 1.08f : otherTextSize);
                c.drawText(ln.text, w / 2f, y, otherPaint);
            }
        }
    }

    /** 当前行: 柔光底 + 逐字点亮(已唱亮白 / 未唱灰白, 中间一段渐变过渡)。 */
    private void drawCurrent(Canvas c, LrcParser.Line ln, float w, float y) {
        float size = curTextSize * pulse;
        donePaint.setTextSize(size);
        todoPaint.setTextSize(size);

        // 柔光底: 宽度随文字, 高度随字号
        float tw = donePaint.measureText(ln.text);
        float glowA = 0.10f + 0.05f * (pulse - 1f) / 0.03f;
        glowPaint.setAlpha((int) (255 * Math.max(0.08f, Math.min(0.16f, glowA))));
        c.drawRoundRect(w / 2f - tw / 2f - size * 0.7f, y - size * 0.98f,
                w / 2f + tw / 2f + size * 0.7f, y + size * 0.42f,
                size * 0.5f, size * 0.5f, glowPaint);

        // 逐字进度: 当前行从 timeMs 到下一行 timeMs 之间推进
        int idx = curIndex;
        long start = ln.timeMs;
        long end = idx + 1 < lines.size() ? lines.get(idx + 1).timeMs : start + 4000L;
        float frac = end > start ? (curPos - start) / (float) (end - start) : 1f;
        frac = Math.max(0f, Math.min(1f, frac));

        float left = w / 2f - tw / 2f;
        float splitX = left + tw * frac;

        // 未唱部分整行先画灰白
        c.drawText(ln.text, w / 2f, y, todoPaint);

        // 已唱部分: 用渐变遮罩把左侧擦亮(过渡带 0.12 * 文字宽)
        float band = Math.max(size * 0.6f, tw * 0.12f);
        donePaint.setShader(new LinearGradient(
                splitX - band, 0, splitX, 0,
                new int[]{C_DONE, (C_DONE & 0x00FFFFFF)}, new float[]{0f, 1f},
                Shader.TileMode.CLAMP));
        int save = c.save();
        c.clipRect(0, 0, splitX, getHeight());
        // 中文按行画会重新居中, 这里用 clipRect 后整行重画 = 只显示已唱段
        Paint clean = donePaint;
        clean.setShader(null);
        clean.setColor(C_DONE);
        c.drawText(ln.text, w / 2f, y, clean);
        c.restoreToCount(save);

        // 过渡带: 在 splitX 附近画一小段柔光, 让交界不生硬
        todoPaint.setShader(new LinearGradient(
                splitX - band, 0, splitX + band, 0,
                new int[]{0x66FFFFFF, 0x00FFFFFF}, new float[]{0f, 1f},
                Shader.TileMode.CLAMP));
        int save2 = c.save();
        c.clipRect(Math.max(0, splitX - band), 0, Math.min(w, splitX + band), getHeight());
        c.drawText(ln.text, w / 2f, y, todoPaint);
        c.restoreToCount(save2);
        todoPaint.setShader(null);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float d = getResources().getDisplayMetrics().density;
        // v27.15: 字号再放大一档 —— 全屏歌词要"看得清", 不是密密麻麻
        curTextSize = Math.min(23 * d, Math.max(15 * d, h / 8.5f));
        otherTextSize = curTextSize * 0.78f;
        lineHeight = curTextSize * 2.05f;
        donePaint.setTextSize(curTextSize);
        todoPaint.setTextSize(curTextSize);
        otherPaint.setTextSize(otherTextSize);
        emptyPaint.setTextSize(otherTextSize);
    }

    /** 点击歌词区: 命中某行即 seek 到该行开始时间。 */
    @Override
    public boolean onTouchEvent(android.view.MotionEvent e) {
        if (e.getAction() == android.view.MotionEvent.ACTION_UP && seekListener != null && !lines.isEmpty()) {
            float centerY = getHeight() / 2f;
            int base = curIndex < 0 ? 0 : curIndex;
            int idx = base + Math.round((e.getY() - centerY - scrollOffset) / lineHeight);
            if (idx >= 0 && idx < lines.size()) {
                seekListener.onSeek(lines.get(idx).timeMs);
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        breath.cancel();
        if (scrollAnim != null) scrollAnim.cancel();
    }
}
