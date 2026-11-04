package com.github.tvbox.osc.music;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.github.tvbox.osc.R;

/**
 * v27.18: 音质选择弹层 —— 原来全屏页的音质胶囊是「点一下切下一档」循环，
 * 用户看到的只有 128k / 320k / flac 这种数字档，既不知道有母带，也不知道各档差别。
 * 现在改成底部弹层：中文档位名 + 一句说明 + 当前档打勾，线路不支持的档位置灰。
 */
public final class MusicQualityDialog {

    /** 固定展示的五档(与后端 source 参数一致)，线路不支持的置灰但要显示出来。 */
    private static final String[][] TIERS = {
            {"master", "母带", "原始录音室质量 · 24bit"},
            {"hires", "Hi-Res", "高解析 · 48kHz 以上"},
            {"flac", "FLAC 无损", "无损压缩 · CD 级"},
            {"320k", "320K 高品质", "通用兼容 · 流量适中"},
            {"128k", "128K 标准", "体积最小 · 流量最省"},
    };

    private MusicQualityDialog() {
    }

    public static void show(final Activity act, final Runnable onPicked) {
        if (act == null || act.isFinishing()) return;
        final MusicPlayer p = MusicPlayer.get();
        final String[] avail = p.availableQualities();
        final String cur = p.preferredQuality();

        final Dialog dlg = new Dialog(act);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setContentView(R.layout.dlg_music_quality);

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

        TextView hint = dlg.findViewById(R.id.qualityHint);
        if (hint != null) {
            hint.setText("当前线路 " + p.lineName() + (contains(avail, "master") ? " · 支持母带" : " · 不支持母带"));
        }

        LinearLayout list = dlg.findViewById(R.id.qualityList);
        if (list != null) {
            for (final String[] tier : TIERS) {
                list.addView(buildRow(act, dlg, p, tier, contains(avail, tier[0]),
                        TextUtils.equals(cur, tier[0]), onPicked));
            }
            // 笒音线路只有 lossless 一档，固定五档里没有它，补一行免得用户以为没得选
            if (contains(avail, "lossless") && !contains(avail, "master")) {
                list.addView(buildRow(act, dlg, p,
                        new String[]{"lossless", "无损 · 母带级", "该线路固定档位"},
                        true, TextUtils.equals(cur, "lossless"), onPicked));
            }
        }

        dlg.show();
    }

    private static View buildRow(final Activity act, final Dialog dlg, final MusicPlayer p,
                                 final String[] tier, final boolean enabled, final boolean selected,
                                 final Runnable onPicked) {
        int padH = act.getResources().getDimensionPixelSize(R.dimen.vs_32);
        int rowH = act.getResources().getDimensionPixelSize(R.dimen.vs_120);
        int mark = act.getResources().getDimensionPixelSize(R.dimen.vs_44);
        int gap = act.getResources().getDimensionPixelSize(R.dimen.vs_20);

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(padH, 0, padH, 0);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, rowH);
        row.setLayoutParams(rlp);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(act.getResources().getDimensionPixelSize(R.dimen.vs_12));
        bg.setColor(selected ? 0x1F7AA8FF : Color.TRANSPARENT);
        row.setBackground(bg);

        // 勾选圈
        TextView check = new TextView(act);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(mark, mark);
        check.setLayoutParams(clp);
        check.setGravity(Gravity.CENTER);
        GradientDrawable cbg = new GradientDrawable();
        cbg.setShape(GradientDrawable.OVAL);
        if (selected) {
            cbg.setColor(0xFF7AA8FF);
            check.setText("✓");
            check.setTextColor(0xFF0A0F1C);
        } else {
            cbg.setColor(Color.TRANSPARENT);
            cbg.setStroke(Math.max(1, act.getResources().getDimensionPixelSize(R.dimen.vs_2)),
                    enabled ? 0xFF39435C : 0xFF2A3348);
        }
        check.setBackground(cbg);
        check.setTextSize(0, act.getResources().getDimension(R.dimen.ts_24));
        row.addView(check);

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        colp.leftMargin = gap;
        col.setLayoutParams(colp);

        TextView name = new TextView(act);
        name.setText(tier[1]);
        name.setTextSize(0, act.getResources().getDimension(R.dimen.ts_30));
        name.setTextColor(enabled ? 0xFFF2F4F8 : 0xFF4E5872);
        col.addView(name);

        TextView desc = new TextView(act);
        desc.setText(enabled ? tier[2] : "当前线路不支持");
        desc.setTextSize(0, act.getResources().getDimension(R.dimen.ts_24));
        desc.setTextColor(enabled ? 0xFF8A93A8 : 0xFF4E5872);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = act.getResources().getDimensionPixelSize(R.dimen.vs_4);
        desc.setLayoutParams(dlp);
        col.addView(desc);

        row.addView(col);
        row.setEnabled(enabled);
        row.setAlpha(enabled ? 1f : 0.55f);

        row.setOnClickListener(v -> {
            if (!enabled) return;
            if (p.setQuality(tier[0])) {
                dlg.dismiss();
                if (onPicked != null) onPicked.run();
            }
        });
        return row;
    }

    private static boolean contains(String[] arr, String v) {
        if (arr == null) return false;
        for (String s : arr) if (TextUtils.equals(s, v)) return true;
        return false;
    }
}
