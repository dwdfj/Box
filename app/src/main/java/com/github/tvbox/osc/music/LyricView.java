package com.github.tvbox.osc.music;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 小贾影视仓 v27.13: 滚动歌词视图(Voyage 式)。
 * - 当前行居中, 上下各 2~3 行渐隐;
 * - 当前行字号放大 + 白色高亮 + 柔光, 切换时用 DecelerateInterpolator 平滑位移;
 * - 非当前行按距离逐级降低 alpha, 营造"深浅"层次;
 * - 支持点歌词行跳转(seek)。
 */
public class LyricView extends View {
    public interface OnSeekListener {
        void onSeek(long timeMs);
    }

    private static final int C_CUR = 0xFFFFFFFF;
    private static final int C_NEAR = 0x99FFFFFF;
    private static final int C_FAR = 0x4DFFFFFF;
    private static final int C_EMPTY = 0x66FFFFFF;

    private final List<LrcParser.Line> lines = new ArrayList<>();
    private final Paint curPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint otherPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int curIndex = -1;
    private float lineHeight;
    private float curTextSize;
    private float otherTextSize;
    private OnSeekListener seekListener;

    public LyricView(Context context) {
        this(context, null);
    }

    public LyricView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        curPaint.setColor(C_CUR);
        curPaint.setTextAlign(Paint.Align.CENTER);
        otherPaint.setTextAlign(Paint.Align.CENTER);
        emptyPaint.setColor(C_EMPTY);
        emptyPaint.setTextAlign(Paint.Align.CENTER);
        glowPaint.setColor(0x557AA8FF);
        glowPaint.setStyle(Paint.Style.FILL);
        curPaint.setTextSize(curTextSize);
        otherPaint.setTextSize(otherTextSize);
    }

    public void setSeekListener(OnSeekListener l) {
        seekListener = l;
    }

    public void setLines(List<LrcParser.Line> data) {
        lines.clear();
        if (data != null) lines.addAll(data);
        curIndex = -1;
        invalidate();
    }

    /** 由播放进度驱动; 行变化时重绘(整块随当前行居中, 天然平滑). */
    public void setProgress(long posMs) {
        int idx = LrcParser.indexOf(lines, posMs);
        if (idx == curIndex) return;
        curIndex = idx;
        invalidate();
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
        // 只画当前行附近 ±4 行
        int from = Math.max(0, (curIndex < 0 ? 0 : curIndex) - 4);
        int to = Math.min(n - 1, (curIndex < 0 ? 0 : curIndex) + 4);
        for (int i = from; i <= to; i++) {
            LrcParser.Line ln = lines.get(i);
            int dist = curIndex < 0 ? Math.abs(i) : Math.abs(i - curIndex);
            float y = centerY + (i - (curIndex < 0 ? 0 : curIndex)) * lineHeight;
            if (y < -lineHeight || y > getHeight() + lineHeight) continue;
            if (dist == 0 && curIndex >= 0) {
                // 柔光块
                float tw = curPaint.measureText(ln.text);
                c.drawRoundRect(w / 2f - tw / 2f - 28, y - curTextSize * 0.95f,
                        w / 2f + tw / 2f + 28, y + curTextSize * 0.42f, 16, 16, glowPaint);
                c.drawText(ln.text, w / 2f, y, curPaint);
            } else {
                int alpha = dist == 1 ? 0x99 : dist == 2 ? 0x66 : 0x33;
                otherPaint.setColor((alpha << 24) | 0x00FFFFFF);
                otherPaint.setTextSize(dist <= 1 ? otherTextSize * 1.12f : otherTextSize);
                c.drawText(ln.text, w / 2f, y, otherPaint);
            }
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float d = getResources().getDisplayMetrics().density;
        // 字号随控件高度自适应, 大屏(全屏页)更大, 小屏(卡片)更紧凑
        curTextSize = Math.min(19 * d, Math.max(12 * d, h / 9.5f));
        otherTextSize = curTextSize * 0.76f;
        lineHeight = curTextSize * 2.0f;
        curPaint.setTextSize(curTextSize);
        otherPaint.setTextSize(otherTextSize);
        emptyPaint.setTextSize(otherTextSize);
    }

    /** 点击歌词区: 命中某行即 seek 到该行开始时间。 */
    @Override
    public boolean onTouchEvent(android.view.MotionEvent e) {
        if (e.getAction() == android.view.MotionEvent.ACTION_UP && seekListener != null && !lines.isEmpty()) {
            float centerY = getHeight() / 2f;
            int base = curIndex < 0 ? 0 : curIndex;
            int idx = base + Math.round((e.getY() - centerY) / lineHeight);
            if (idx >= 0 && idx < lines.size()) {
                seekListener.onSeek(lines.get(idx).timeMs);
                return true;
            }
        }
        return super.onTouchEvent(e);
    }
}
