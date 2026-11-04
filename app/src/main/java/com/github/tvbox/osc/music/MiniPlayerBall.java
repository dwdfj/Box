package com.github.tvbox.osc.music;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.ui.activity.MusicNowActivity;
import com.github.tvbox.osc.util.ImgUtil;

import java.lang.ref.WeakReference;

/**
 * 小贾影视仓 v27.32: 应用内悬浮播放球(对齐 Voyage 的"退出播放页仍在播、球可拖到任意角落")。
 * v27.34: 修"退到影视界面球就没了" + 加特效。
 * <p>
 * 结构: 外层 FrameLayout(outer = size × 1.7) 里居中放一个 size 的 bubble,
 * 多出来的空间给"脉冲光环"用 —— 否则光晕会被 decorView 的 clipChildren 裁掉。
 * <p>
 * 1) 由 {@link BallHost} 在每次 Activity onResume 时把球挂到该窗口的 decorView;
 * 2) 拖动直接改 setX/setY, 松手吸附最近一侧, 位置按父容器比例存;
 * 3) 单击 = 进全屏播放页, 长按 520ms = 播放/暂停;
 * 4) 特效: 播放时双层脉冲光环 + 封面缓慢旋转 + 环形进度; 拖动时倾斜并抬升。
 */
public class MiniPlayerBall extends FrameLayout implements MusicPlayer.Listener {

    private static final String PREF = "xj_music_ball";
    private static final float DEFAULT_X = 0.86f;
    private static final float DEFAULT_Y = 0.62f;
    private static final long LONG_PRESS_MS = 520;
    /** 外框相对球体的倍数 —— 留出光环扩散的余量。 */
    private static final float OUTER_SCALE = 1.7f;

    private static MiniPlayerBall sInstance;

    private final FrameLayout bubble;
    private final HaloView halo;
    private final ImageView cover;
    private final ImageView badge;
    private final CoverRingView ring;
    private final SharedPreferences sp;
    private final int size;
    private final int outer;
    private final int pad;
    private final int slop;
    private final int statusBar;

    private ValueAnimator haloAnim;

    private WeakReference<Activity> host;
    private boolean listening;
    private String lastCover;
    private float fx = DEFAULT_X;
    private float fy = DEFAULT_Y;
    private float rotation;
    private boolean moved;
    private float downRawX, downRawY, downX, downY;
    private long downAt;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                syncProgress();
            } catch (Throwable ignored) {
            }
            if (getParent() != null) postDelayed(this, 500);
        }
    };

    private MiniPlayerBall(Context c) {
        super(c);
        int screenW = c.getResources().getDisplayMetrics().widthPixels;
        // 用屏宽比例定尺寸, 不跟 AutoSize 的 mm 缩放走 —— 否则在 1280 设计宽度下球只有几十像素。
        size = Math.max(96, Math.round(screenW * 0.13f));
        outer = Math.round(size * OUTER_SCALE);
        pad = Math.max(2, size / 20);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        statusBar = statusBarHeight(c);
        sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        fx = sp.getFloat("x", DEFAULT_X);
        fy = sp.getFloat("y", DEFAULT_Y);

        setClickable(true);
        setFocusable(false);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        setClipChildren(false);
        setClipToPadding(false);

        // ① 光环(最底层)
        halo = new HaloView(c);
        addView(halo, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // ② 球体
        bubble = new FrameLayout(c);
        bubble.setClipChildren(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xF216131C);
        bg.setStroke(Math.max(2, size / 44), 0x40FFFFFF);
        bubble.setBackground(bg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            bubble.setElevation(size * 0.20f);
        }
        LayoutParams blp = new LayoutParams(size, size);
        blp.gravity = Gravity.CENTER;
        addView(bubble, blp);

        ring = new CoverRingView(c);
        bubble.addView(ring, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        int inset = Math.round(size * 0.10f);
        int cs = size - inset * 2;
        cover = new ImageView(c);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setImageResource(R.drawable.hm_music);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(cs, cs);
        clp.gravity = Gravity.CENTER;
        bubble.addView(cover, clp);

        int bs = Math.round(size * 0.42f);
        badge = new ImageView(c);
        badge.setImageResource(R.drawable.ic_mini_play);
        GradientDrawable bbg = new GradientDrawable();
        bbg.setShape(GradientDrawable.OVAL);
        bbg.setColor(0xE61B1820);
        badge.setBackground(bbg);
        int bp = Math.max(2, bs / 4);
        badge.setPadding(bp, bp, bp, bp);
        FrameLayout.LayoutParams bgp = new FrameLayout.LayoutParams(bs, bs);
        bgp.gravity = Gravity.CENTER;
        bubble.addView(badge, bgp);
        badge.setVisibility(GONE);

        startHalo();
    }

    public static MiniPlayerBall get(Context c) {
        if (sInstance == null) sInstance = new MiniPlayerBall(c.getApplicationContext());
        return sInstance;
    }

    /** 挂到当前 Activity 的窗口最上层(幂等; 已在同窗口则只刷新状态)。 */
    public void attach(Activity a) {
        if (a == null || a.isFinishing()) return;
        ViewGroup root;
        try {
            root = (ViewGroup) a.getWindow().getDecorView();
        } catch (Throwable t) {
            return;
        }
        if (root == null) return;
        host = new WeakReference<>(a);
        if (getParent() == root) {
            bringToFront();
            refresh();
            startTick();
            return;
        }
        // v27.34: 旧实现只 try removeView, 若旧 decorView 已 detach 会静默失败,
        // 随后 addView 抛 "specified child already has a parent" -> catch -> 球就没了。
        // 现在: 先强摘(removeViewImmediate), 再普通 remove, 仍失败就丢弃实例重建。
        if (!detachFromParent()) {
            sInstance = null;
            try {
                MiniPlayerBall fresh = new MiniPlayerBall(getContext());
                MiniPlayerBall.sInstance = fresh;
                fresh.attach(a);
            } catch (Throwable ignored) {
            }
            return;
        }
        LayoutParams lp = new LayoutParams(outer, outer);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        try {
            root.addView(this, lp);
            bringToFront();
        } catch (Throwable t) {
            sInstance = null;
            try {
                MiniPlayerBall fresh = new MiniPlayerBall(getContext());
                MiniPlayerBall.sInstance = fresh;
                root.addView(fresh, lp);
                fresh.bringToFront();
                fresh.post(fresh.positionRunnable);
                fresh.startTick();
            } catch (Throwable ignored) {
            }
            return;
        }
        post(positionRunnable);
    }

    private final Runnable positionRunnable = new Runnable() {
        @Override
        public void run() {
            if (getParent() == null) return;
            applyPos(false);
            refresh();
            startTick();
        }
    };

    private boolean detachFromParent() {
        ViewGroup p = getParent() instanceof ViewGroup ? (ViewGroup) getParent() : null;
        if (p == null) return true;
        try {
            p.removeView(this);
        } catch (Throwable ignored) {
        }
        if (getParent() == null) return true;
        try {
            p.removeViewInLayout(this);
        } catch (Throwable ignored) {
        }
        return getParent() == null;
    }

    /** 从窗口摘掉(不销毁, 下次 onResume 再挂)。 */
    public static void detach() {
        MiniPlayerBall b = sInstance;
        if (b == null) return;
        b.stopTick();
        b.unlisten();
        b.stopHalo();
        b.detachFromParent();
        b.host = null;
        // v27.34: 置空 -> 下次 attach 一定新建实例, 彻底避开"旧 decorView 仍持有 View"的死锁。
        sInstance = null;
    }

    // ---------------- 光环特效 ----------------

    private void startHalo() {
        try {
            haloAnim = ValueAnimator.ofFloat(0f, 1f);
            haloAnim.setDuration(1900);
            haloAnim.setRepeatCount(ValueAnimator.INFINITE);
            haloAnim.setInterpolator(new LinearInterpolator());
            haloAnim.addUpdateListener(a -> halo.setPhase((float) a.getAnimatedValue()));
            haloAnim.start();
        } catch (Throwable ignored) {
        }
    }

    private void stopHalo() {
        try {
            if (haloAnim != null) haloAnim.cancel();
        } catch (Throwable ignored) {
        }
        haloAnim = null;
        halo.setPhase(-1f);
    }

    /** 双层扩散光环 —— 纯 Canvas 绘制, 不额外占布局。 */
    private static final class HaloView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float phase = 0f;

        HaloView(Context c) {
            super(c);
            paint.setStyle(Paint.Style.STROKE);
        }

        void setPhase(float v) {
            phase = v;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            if (phase < 0f) return;
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float edge = Math.min(cx, cy) - 2f;
            float ballR = edge * (1f / OUTER_SCALE) * 0.96f;
            for (int i = 0; i < 2; i++) {
                float ph = (phase + i * 0.5f) % 1f;
                float r = ballR + (edge - ballR) * ph;
                int alpha = (int) (128 * (1f - ph) * (1f - ph));
                paint.setColor((alpha << 24) | 0x7AA8FF);
                paint.setStrokeWidth(Math.max(1.5f, edge * 0.05f * (1f - ph * 0.55f)));
                c.drawCircle(cx, cy, r, paint);
            }
        }
    }

    // ---------------- 位置 ----------------

    private void applyPos(boolean animate) {
        View p = (View) getParent();
        if (p == null) return;
        int w = p.getWidth(), h = p.getHeight();
        if (w <= 0 || h <= 0) {
            // 布局还没完成 —— 再等一帧, 否则球会停在 (0,0) 被顶栏盖住, 看起来就是"没了"
            postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (getParent() != null) applyPos(false);
                }
            }, 120);
            return;
        }
        float x = clampX(fx * (w - outer));
        float y = clampY(fy * (h - outer));
        if (animate) {
            animate().x(x).y(y).setDuration(180).start();
        } else {
            setX(x);
            setY(y);
        }
    }

    private float clampX(float x) {
        View p = (View) getParent();
        int w = p == null ? 0 : p.getWidth();
        if (w <= 0) return x;
        return Math.max(pad, Math.min(w - outer - pad, x));
    }

    private float clampY(float y) {
        View p = (View) getParent();
        int h = p == null ? 0 : p.getHeight();
        if (h <= 0) return y;
        return Math.max(pad + statusBar, Math.min(h - outer - pad, y));
    }

    /** 松手吸附到最近的一侧边缘。 */
    private void snap() {
        View p = (View) getParent();
        if (p == null) return;
        int w = p.getWidth();
        if (w <= 0) return;
        float tx = (getX() + outer / 2f) < w / 2f ? pad : w - outer - pad;
        animate().x(tx).setDuration(180).withEndAction(new Runnable() {
            @Override
            public void run() {
                savePos();
            }
        }).start();
    }

    private void savePos() {
        View p = (View) getParent();
        if (p == null) return;
        int w = p.getWidth(), h = p.getHeight();
        if (w <= outer || h <= outer) return;
        fx = Math.max(0f, Math.min(1f, getX() / (float) (w - outer)));
        fy = Math.max(0f, Math.min(1f, getY() / (float) (h - outer)));
        sp.edit().putFloat("x", fx).putFloat("y", fy).apply();
    }

    private static int statusBarHeight(Context c) {
        try {
            int id = c.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) return c.getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) {
        }
        return Math.round(24 * c.getResources().getDisplayMetrics().density);
    }

    // ---------------- 触摸 ----------------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                moved = false;
                downRawX = e.getRawX();
                downRawY = e.getRawY();
                downX = getX();
                downY = getY();
                downAt = SystemClock.uptimeMillis();
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                bubble.animate().scaleX(1.10f).scaleY(1.10f).setDuration(90).start();
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dx = e.getRawX() - downRawX;
                float dy = e.getRawY() - downRawY;
                if (!moved && Math.hypot(dx, dy) > slop) moved = true;
                if (moved) {
                    setX(clampX(downX + dx));
                    setY(clampY(downY + dy));
                    // 拖动时随位移轻微倾斜, 手感更"活"
                    float tilt = Math.max(-7f, Math.min(7f, dx * 0.02f));
                    bubble.setRotation(tilt);
                }
                return true;
            }

            case MotionEvent.ACTION_UP: {
                bubble.animate().scaleX(1f).scaleY(1f).setDuration(90).start();
                bubble.animate().rotation(0f).setDuration(160).start();
                long dt = SystemClock.uptimeMillis() - downAt;
                if (moved) {
                    snap();
                } else if (dt >= LONG_PRESS_MS) {
                    MusicPlayer.get().toggle();
                    refresh();
                } else {
                    openPlayer();
                }
                return true;
            }

            case MotionEvent.ACTION_CANCEL:
                bubble.animate().scaleX(1f).scaleY(1f).setDuration(90).start();
                bubble.animate().rotation(0f).setDuration(160).start();
                if (moved) snap();
                return true;
        }
        return super.onTouchEvent(e);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void openPlayer() {
        Activity a = host == null ? null : host.get();
        if (a == null) return;
        try {
            a.startActivity(new Intent(a, MusicNowActivity.class));
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 状态同步 ----------------

    private void startTick() {
        listen();
        refresh();
        removeCallbacks(tick);
        post(tick);
    }

    private void stopTick() {
        removeCallbacks(tick);
    }

    private void listen() {
        if (listening) return;
        try {
            MusicPlayer.get().addListener(this);
            listening = true;
        } catch (Throwable ignored) {
        }
    }

    private void unlisten() {
        if (!listening) return;
        listening = false;
        try {
            MusicPlayer.get().removeListener(this);
        } catch (Throwable ignored) {
        }
    }

    private void syncProgress() {
        MusicPlayer p = MusicPlayer.get();
        if (!p.isPlaying()) return;
        float r = p.progressRatio();
        ring.setProgress(r < 0 ? 0 : r);
        rotation += 2.4f;
        if (rotation > 360f) rotation -= 360f;
        cover.setRotation(rotation);
    }

    /** 按播放器真实状态刷新封面/暂停角标/进度环/光环。 */
    private void refresh() {
        MusicPlayer p = MusicPlayer.get();
        MusicApi.Song s = p.current();
        boolean playing = p.isPlaying();
        halo.setVisibility(playing ? VISIBLE : INVISIBLE);
        if (s == null) {
            cover.setAlpha(0.55f);
            badge.setVisibility(VISIBLE);
            ring.setVisibility(INVISIBLE);
            return;
        }
        String c = s.cover == null ? "" : s.cover;
        if (!TextUtils.equals(lastCover, c)) {
            lastCover = c;
            int cs = (int) (size * 0.8f);
            if (TextUtils.isEmpty(c)) {
                cover.setImageResource(R.drawable.hm_music);
            } else {
                ImgUtil.load(c, cover, cs / 2);
            }
        }
        cover.setAlpha(playing ? 1f : 0.55f);
        badge.setVisibility(playing ? GONE : VISIBLE);
        ring.setVisibility(playing ? VISIBLE : INVISIBLE);
        if (!playing) {
            cover.setRotation(0f);
            rotation = 0f;
        }
    }

    @Override
    public void onStatus(String text) {
    }

    @Override
    public void onPlaying(MusicApi.Song song, String sourceName, String quality) {
        refresh();
    }

    @Override
    public void onPaused() {
        refresh();
    }

    @Override
    public void onError(String text) {
    }
}
