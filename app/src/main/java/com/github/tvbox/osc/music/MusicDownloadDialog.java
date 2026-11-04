package com.github.tvbox.osc.music;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.github.tvbox.osc.R;

/**
 * 小贾影视仓 v27.26: 下载位置弹层。
 *
 * 原来「下载」是直接落盘, 想换文件夹得先点另一个「目录」按钮 —— 用户根本不知道那是干什么的。
 * 现在点「下载」先弹这一层: 上面写当前会存到哪, 下面「立即下载 / 选择其他文件夹 / 恢复默认」,
 * 保存位置这件事在下载这一刻就能改。
 */
public final class MusicDownloadDialog {

    private MusicDownloadDialog() {
    }

    public static void show(final Activity act, final int reqPickDir, final Runnable onStart) {
        if (act == null || act.isFinishing()) return;

        final Dialog dlg = new Dialog(act);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setContentView(R.layout.dlg_music_download);

        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.6f;
            w.setAttributes(lp);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }

        final String label = MusicDownloadDir.label();
        TextView hint = dlg.findViewById(R.id.dlHint);
        if (hint != null) {
            hint.setText(label == null
                    ? "保存位置：默认目录（手机存储 / Music / 小贾影视仓）"
                    : "保存位置：" + label);
        }

        LinearLayout list = dlg.findViewById(R.id.dlList);
        if (list != null) {
            list.addView(row(act, dlg, "立即下载", "按上面的位置保存这首歌", true, () -> {
                if (onStart != null) onStart.run();
            }));
            list.addView(row(act, dlg, "选择其他文件夹", "打开系统文件选择器，自己挑一个目录", false, () -> {
                MusicDownloadDir.pick(act, reqPickDir);
            }));
            if (label != null) {
                list.addView(row(act, dlg, "恢复默认目录", "改回 手机存储 / Music / 小贾影视仓", false, () -> {
                    MusicDownloadDir.clear();
                }));
            }
        }

        dlg.show();
    }

    private static View row(final Activity act, final Dialog dlg, String title, String desc,
                            boolean accent, final Runnable action) {
        int padH = act.getResources().getDimensionPixelSize(R.dimen.vs_32);
        int rowH = accent
                ? act.getResources().getDimensionPixelSize(R.dimen.vs_140)
                : act.getResources().getDimensionPixelSize(R.dimen.vs_120);
        int gap = act.getResources().getDimensionPixelSize(R.dimen.vs_20);
        int radius = act.getResources().getDimensionPixelSize(R.dimen.vs_24);

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(padH, 0, padH, 0);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, rowH);
        rlp.leftMargin = gap;
        rlp.rightMargin = gap;
        row.setLayoutParams(rlp);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(radius);
        bg.setColor(accent ? 0xFF7AA8FF : 0x14FFFFFF);
        row.setBackground(bg);

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(act);
        t.setText(title);
        t.setTextSize(0, act.getResources().getDimension(R.dimen.ts_34));
        t.setTextColor(accent ? 0xFF0A0F1C : 0xFFF2F4F8);
        t.setSingleLine(true);
        col.addView(t);

        TextView d = new TextView(act);
        d.setText(desc);
        d.setTextSize(0, act.getResources().getDimension(R.dimen.ts_24));
        d.setTextColor(accent ? 0xCC0A0F1C : 0xFF8A93A8);
        d.setSingleLine(true);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = act.getResources().getDimensionPixelSize(R.dimen.vs_4);
        d.setLayoutParams(dlp);
        col.addView(d);

        row.addView(col);
        row.setOnClickListener(v -> {
            dlg.dismiss();
            action.run();
        });
        return row;
    }
}
