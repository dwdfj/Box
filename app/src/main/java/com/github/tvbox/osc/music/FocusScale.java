package com.github.tvbox.osc.music;

import android.view.View;

/**
 * 小贾影视仓 v27.5: TV 焦点缩放(150ms, 默认 1.08x / banner 1.04x) —— 参考项目 FastSearchActivity
 * 的弹性缩放范式, 统一音乐模块的海报墙/歌曲行手感; 非聚焦降透明度。
 */
public final class FocusScale {
    public static void attach(View v) {
        attach(v, 1.08f);
    }

    public static void attach(View v, float scale) {
        v.setOnFocusChangeListener((view, hasFocus) -> {
            view.animate()
                    .scaleX(hasFocus ? scale : 1.0f)
                    .scaleY(hasFocus ? scale : 1.0f)
                    .setDuration(150)
                    .start();
            view.setAlpha(hasFocus ? 1.0f : 0.86f);
        });
    }
}
