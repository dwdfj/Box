package com.github.tvbox.osc.music;

import android.app.Application;
import android.content.Context;

/**
 * 小贾影视仓 v27.3: 给 music 包提供 Application 上下文(MusicPlayer 单例在无 Activity 时也能拿资源)。
 */
public class AppContextHolder {
    private static Context sApp;

    public static void init(Context app) {
        sApp = app;
    }

    public static Context get() {
        return sApp;
    }
}
