package com.github.tvbox.osc.music;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.ui.activity.MusicNowActivity;
import com.github.tvbox.osc.util.ImgUtil;

import java.lang.ref.WeakReference;

/**
 * 小贾影视仓 v27.32: 应用内悬浮播放球(对齐 Voyage 的"退出播放页仍在播、球可拖到任意角落")。
 * <p>
 * 实现要点(全部在应用内, 不申请 SYSTEM_ALERT_WINDOW):
 * 1) 由 {@link BallHost} 在每次 Activity onResume 时把本 View 挂到该窗口的 decorView,
 *    onPause 时摘掉 —— 于是"翻页后球还在", 实际是跟着新页面重新挂载, 不存在跨进程/跨窗口;
 * 2) 拖动直接把 View 的 setX/setY 改掉(父容器是 FrameLayout, 不受布局约束), 松手吸附到最近一侧,
 *    位置按"父容器宽高的比例"存 SharedPreferences, 换页面/换分辨率都还在原来那个角落;
 * 3) 单击 = 进全屏播放页, 长按 520ms = 播放/暂停 —— 与常见悬浮球手感一致;
 * 4) 圆形封面 + {@link CoverRingView} 进度环 + 暂停态的半透明 + 中心播放三角;
 * 5) setFocusable(false) + FOCUS_BLOCK_DESCENDANTS —— 电视遥控器的焦点不会跑到球上。
 */
public class MiniPlayerBall extends FrameLayout implements MusicPlayer.Listener {

    private static final String PREF = "xj_music_ball";
    private static final float DEFAULT_X = 0.86f;
    private static final float DEFAULT_Y = 0.62f;
    /** 长按判定(ms): 超过它且没移动 = 播放/暂停。 */
    private static final long LONG_PRESS_MS = 520;

    private static MiniPlayerBall sInstance;

    private final ImageView cover;
    private final ImageView badge;
    private final CoverRingView ring;
    private final SharedPreferences sp;
    private final int size;
    private final int pad;
    private final int slop;
    private final int statusBar;

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
        pad = Math.max(2, size / 20);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        statusBar = statusBarHeight(c);
        sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        fx = sp.getFloat("x", DEFAULT_X);
        fy = sp.getFloat("y", DEFAULT_Y);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xF216131C);
        bg.setStroke(Math.max(2, size / 44), 0x40FFFFFF);
        setBackground(bg);
        setClickable(true);
        setFocusable(false);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            setElevation(size * 0.20f);
        }

        ring = new CoverRingView(c);
        addView(ring, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        int inset = Math.round(size * 0.10f);
        int cs = size - inset * 2;
        cover = new ImageView(c);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setImageResource(R.drawable.hm_music);
        LayoutParams clp = new LayoutParams(cs, cs);
        clp.gravity = Gravity.CENTER;
        addView(cover, clp);

        int bs = Math.round(size * 0.42f);
        badge = new ImageView(c);
        badge.setImageResource(R.drawable.ic_mini_play);
        GradientDrawable bbg = new GradientDrawable();
        bbg.setShape(GradientDrawable.OVAL);
        bbg.setColor(0xE61B1820);
        badge.setBackground(bbg);
        int bp = Math.max(2, bs / 4);
        badge.setPadding(bp, bp, bp, bp);
        LayoutParams blp = new LayoutParams(bs, bs);
        blp.gravity = Gravity.CENTER;
        addView(badge, blp);
        badge.setVisibility(GONE);
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
            refresh();
            startTick();
            return;
        }
        if (getParent() instanceof ViewGroup) {
            try {
                ((ViewGroup) getParent()).removeView(this);
            } catch (Throwable ignored) {
            }
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        try {
            root.addView(this, lp);
        } catch (Throwable t) {
            return;
        }
        post(new Runnable() {
            @Override
            public void run() {
                if (getParent() != null) applyPos(false);
                refresh();
                startTick();
            }
        });
    }

    /** 从窗口摘掉(不销毁, 下次 onResume 再挂)。 */
    public static void detach() {
        MiniPlayerBall b = sInstance;
        if (b == null) return;
        b.stopTick();
        b.unlisten();
        try {
            if (b.getParent() instanceof ViewGroup)
                ((ViewGroup) b.getParent()).removeView(b);
        } catch (Throwable ignored) {
        }
        b.host = null;
    }

    // ---------------- 位置 ----------------

    private void applyPos(boolean animate) {
        View p = (View) getParent();
        if (p == null) return;
        int w = p.getWidth(), h = p.getHeight();
        if (w <= 0 || h <= 0) return;
        float x = clampX(fx * (w - size));
        float y = clampY(fy * (h - size));
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
        return Math.max(pad, Math.min(w - size - pad, x));
    }

    private float clampY(float y) {
        View p = (View) getParent();
        int h = p == null ? 0 : p.getHeight();
        if (h <= 0) return y;
        return Math.max(pad + statusBar, Math.min(h - size - pad, y));
    }

    /** 松手吸附到最近的一侧边缘。 */
    private void snap() {
        View p = (View) getParent();
        if (p == null) return;
        int w = p.getWidth();
        if (w <= 0) return;
        float tx = (getX() + size / 2f) < w / 2f ? pad : w - size - pad;
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
        if (w <= size || h <= size) return;
        fx = Math.max(0f, Math.min(1f, getX() / (float) (w - size)));
        fy = Math.max(0f, Math.min(1f, getY() / (float) (h - size)));
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
                animate().scaleX(1.08f).scaleY(1.08f).setDuration(90).start();
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dx = e.getRawX() - downRawX;
                float dy = e.getRawY() - downRawY;
                if (!moved && Math.hypot(dx, dy) > slop) moved = true;
                if (moved) {
                    setX(clampX(downX + dx));
                    setY(clampY(downY + dy));
                }
                return true;
            }

            case MotionEvent.ACTION_UP: {
                animate().scaleX(1f).scaleY(1f).setDuration(90).start();
                long dt = SystemClock.uptimeMillis() - downAt;
                if (moved) {
                    snap();
                } else if (dt >= LONG_PRESS_MS) {
                    // 长按 = 播放/暂停(不跳页, 方便边看视频边切歌)
                    MusicPlayer.get().toggle();
                    refresh();
                } else {
                    openPlayer();
                }
                return true;
            }

            case MotionEvent.ACTION_CANCEL:
                animate().scaleX(1f).scaleY(1f).setDuration(90).start();
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

    /** 按播放器真实状态刷新封面/暂停角标/进度环。 */
    private void refresh() {
        MusicPlayer p = MusicPlayer.get();
        MusicApi.Song s = p.current();
        boolean playing = p.isPlaying();
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
