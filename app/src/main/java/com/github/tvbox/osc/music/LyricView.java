package com.github.tvbox.osc.music;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BlurMaskFilter;
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
 * 小贾影视仓 v27.16: 歌词动效 —— 定稿为「卡拉OK扫光 + 发光描边 + 景深 + 呼吸」。
 *
 * 用户看过 A~F 六种方案后选定的组合(知识库「歌词动效」条目已更新):
 *   ① 卡拉OK扫光: 当前行盖一层亮色底, 随时间从左扫到右, 扫过即变「实心亮白」,
 *      未扫到是半透明灰白。比逐字点亮更连贯, 不会一个字一个字蹦。
 *   ② 发光描边: 扫光经过的那一小段文字带柔和辉光(BlurMaskFilter), 暗底上很出片。
 *   ③ 景深: 当前行最大最亮; ±1 行缩到 0.78、α0.55; ±2 行缩到 0.66、α0.28;
 *      再远不画。这就是 Apple Music 那种「中间清楚、上下虚掉」的纵深感。
 *   ④ 呼吸: 当前行 ±1.5% 缩放, 2600ms 往返。
 *   ⑤ 行切换 280ms DecelerateInterpolator 平滑滚动, 不生硬跳。
 *
 * 性能注意: BlurMaskFilter 在部分低端机上掉帧, 所以辉光只画在扫光头部那一小块区域
 * (clip 到 splitX±band), 不是整行开 blur。
 */
public class LyricView extends View {
    public interface OnSeekListener {
        void onSeek(long timeMs);
    }

    private static final int C_DONE = 0xFFFFFFFF;     // 已唱: 亮白实心
    private static final int C_TODO = 0x55FFFFFF;     // 未唱: 灰白
    private static final int C_ACCENT = 0xFF7AA8FF;   // 强调色(扫光底/辉光)

    private final List<LrcParser.Line> lines = new ArrayList<>();
    private final Paint donePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint todoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint otherPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sweepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);   // 扫光底
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);    // 头部辉光
    private final Paint hairPaint = new Paint(Paint.ANTI_ALIAS_FLAG);    // 扫光头部细线

    private int curIndex = -1;
    private long curPos = 0;
    private float lineHeight;
    private float curTextSize;
    private float otherTextSize;

    private float scrollOffset = 0f;
    private float scrollFrom = 0f;
    private float scrollTo = 0f;
    private ValueAnimator scrollAnim;

    private float pulse = 1f;
    private final ValueAnimator breath;

    private OnSeekListener seekListener;

    public LyricView(Context context) {
        this(context, null);
    }

    public LyricView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        setLayerType(LAYER_TYPE_SOFTWARE, null); // BlurMaskFilter 需要软件层

        donePaint.setTextAlign(Paint.Align.CENTER);
        donePaint.setColor(C_DONE);
        todoPaint.setTextAlign(Paint.Align.CENTER);
        todoPaint.setColor(C_TODO);
        otherPaint.setTextAlign(Paint.Align.CENTER);
        emptyPaint.setTextAlign(Paint.Align.CENTER);
        emptyPaint.setColor(0x88FFFFFF);
        sweepPaint.setStyle(Paint.Style.FILL);
        glowPaint.setStyle(Paint.Style.FILL);
        glowPaint.setColor(C_ACCENT);
        hairPaint.setStyle(Paint.Style.FILL);
        hairPaint.setColor(0x88FFFFFF);

        breath = ValueAnimator.ofFloat(1f, 1.015f);
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

    /** 播放进度驱动: 行变化时平滑滚到新行, 行内推进时扫光前进。 */
    public void setProgress(long posMs) {
        curPos = posMs;
        int idx = LrcParser.indexOf(lines, posMs);
        if (idx != curIndex) {
            int old = curIndex;
            curIndex = idx;
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
        int from = Math.max(0, base - 2);
        int to = Math.min(n - 1, base + 2);

        for (int i = from; i <= to; i++) {
            LrcParser.Line ln = lines.get(i);
            int dist = Math.abs(i - base);
            float y = centerY + (i - base) * lineHeight + scrollOffset;
            if (y < -lineHeight || y > getHeight() + lineHeight) continue;

            if (dist == 0 && curIndex >= 0) {
                drawCurrent(c, ln, w, y);
            } else {
                // ③ 景深: 距离越远越小、越淡、越"虚"
                float scale;
                int alpha;
                switch (dist) {
                    case 1:  scale = 0.78f; alpha = 0x8C; break;
                    case 2:  scale = 0.66f; alpha = 0x48; break;
                    default: scale = 0.58f; alpha = 0x20; break;
                }
                otherPaint.setColor((alpha << 24) | 0x00FFFFFF);
                otherPaint.setTextSize(otherTextSize * scale);
                if (dist == 2) {
                    otherPaint.setMaskFilter(new BlurMaskFilter(curTextSize * 0.10f, BlurMaskFilter.Blur.NORMAL));
                } else if (dist > 2) {
                    otherPaint.setMaskFilter(new BlurMaskFilter(curTextSize * 0.18f, BlurMaskFilter.Blur.NORMAL));
                } else {
                    otherPaint.setMaskFilter(null);
                }
                c.drawText(ln.text, w / 2f, y, otherPaint);
                otherPaint.setMaskFilter(null);
            }
        }
    }

    /** 当前行: 扫光底 + 卡拉OK扫光 + 头部辉光 + 呼吸。 */
    private void drawCurrent(Canvas c, LrcParser.Line ln, float w, float y) {
        float size = curTextSize * pulse;
        donePaint.setTextSize(size);
        todoPaint.setTextSize(size);
        float tw = donePaint.measureText(ln.text);
        float left = w / 2f - tw / 2f;

        // 扫光进度
        int idx = curIndex;
        long start = ln.timeMs;
        long end = idx + 1 < lines.size() ? lines.get(idx + 1).timeMs : start + 4000L;
        float frac = end > start ? (curPos - start) / (float) (end - start) : 1f;
        frac = Math.max(0f, Math.min(1f, frac));
        float splitX = left + tw * frac;

        // ① 整行先画「未唱」灰白
        c.drawText(ln.text, w / 2f, y, todoPaint);

        // ② 已唱段: 裁到 splitX 左侧, 画实心亮白
        int save = c.save();
        c.clipRect(0, 0, splitX, getHeight());
        c.drawText(ln.text, w / 2f, y, donePaint);
        c.restoreToCount(save);

        // ③ 扫光底: 已唱段铺一层很淡的强调色, 让"唱过的部分"有块亮的底盘
        if (frac > 0.002f) {
            sweepPaint.setShader(new LinearGradient(left, 0, splitX, 0,
                    new int[]{0x00FFFFFF, 0x1E7AA8FF, 0x337AA8FF}, new float[]{0f, 0.6f, 1f},
                    Shader.TileMode.CLAMP));
            c.drawRoundRect(left - size * 0.35f, y - size * 0.92f, splitX,
                    y + size * 0.34f, size * 0.42f, size * 0.42f, sweepPaint);
            sweepPaint.setShader(null);
        }

        // ④ 头部辉光: 只 clip 到 splitX 附近一小段, 避免整行 blur 掉帧
        float band = Math.max(size * 0.9f, tw * 0.06f);
        int save2 = c.save();
        c.clipRect(Math.max(0f, splitX - band), 0, Math.min(w, splitX + band * 0.4f), getHeight());
        glowPaint.setShader(new LinearGradient(splitX - band, 0, splitX + band * 0.4f, 0,
                new int[]{0x007AA8FF, 0x887AA8FF}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(Math.max(0f, splitX - band), y - size * 0.95f,
                Math.min(w, splitX + band * 0.4f), y + size * 0.36f, glowPaint);
        glowPaint.setShader(null);
        // 扫光头部一根细亮线
        c.drawRect(splitX - size * 0.03f, y - size * 0.86f, splitX + size * 0.03f, y + size * 0.30f, hairPaint);
        c.restoreToCount(save2);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float d = getResources().getDisplayMetrics().density;
        curTextSize = Math.min(24 * d, Math.max(16 * d, h / 8.0f));
        otherTextSize = curTextSize;
        lineHeight = curTextSize * 2.10f;
        donePaint.setTextSize(curTextSize);
        todoPaint.setTextSize(curTextSize);
        otherPaint.setTextSize(otherTextSize);
        emptyPaint.setTextSize(otherTextSize);
    }

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
