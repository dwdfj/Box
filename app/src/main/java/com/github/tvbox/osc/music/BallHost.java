package com.github.tvbox.osc.music;

import android.app.Activity;
import android.app.Application;
import android.content.pm.PackageManager;
import android.os.Bundle;

import com.github.tvbox.osc.ui.activity.LivePlayActivity;
import com.github.tvbox.osc.ui.activity.MusicNowActivity;
import com.github.tvbox.osc.ui.activity.PlayActivity;

/**
 * 小贾影视仓 v27.32: 悬浮球的挂载调度器。
 * <p>
 * 球不常驻某个窗口, 而是在每次 Activity onResume 时挂到当前窗口的 decorView、onPause 时摘掉:
 * 这样"翻页后球还在"靠的是重新挂载, 不需要悬浮窗权限, 也不会在 App 退到后台后盖在别人脸上。
 * <p>
 * 不显示的页面: 全屏音乐播放页(本身就是播放器)、影视播放页与直播页(别挡画面)。
 */
public final class BallHost implements Application.ActivityLifecycleCallbacks {

    private static boolean installed;
    private static boolean touchable = true;

    private BallHost() {
    }

    public static void install(Application app) {
        if (installed || app == null) return;
        installed = true;
        try {
            touchable = app.getPackageManager()
                    .hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
        } catch (Throwable t) {
            touchable = true;
        }
        try {
            app.registerActivityLifecycleCallbacks(new BallHost());
        } catch (Throwable t) {
            installed = false;
        }
    }

    private static boolean shouldShow(Activity a) {
        if (!touchable) return false;                 // 纯遥控器设备不显示(拖不动, 只是干扰)
        if (a instanceof MusicNowActivity) return false;
        if (a instanceof PlayActivity) return false;
        if (a instanceof LivePlayActivity) return false;
        return true;
    }

    private static void show(Activity a) {
        try {
            if (MusicPlayer.get().current() == null) {
                MiniPlayerBall.detach();
                return;
            }
            MiniPlayerBall.get(a).attach(a);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onActivityResumed(Activity activity) {
        try {
            if (activity == null || activity.isFinishing()) return;
            if (!shouldShow(activity)) {
                MiniPlayerBall.detach();
                return;
            }
            show(activity);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onActivityPaused(Activity activity) {
        try {
            MiniPlayerBall.detach();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
    }

    @Override
    public void onActivityStarted(Activity activity) {
    }

    @Override
    public void onActivityStopped(Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
        try {
            MiniPlayerBall.detach();
        } catch (Throwable ignored) {
        }
    }
}
