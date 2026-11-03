package com.github.tvbox.osc.ui.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.music.MusicDirect;
import com.github.tvbox.osc.music.MusicPlayer;

/**
 * 小贾影视仓 v27.13: 线路选择弹窗 —— 照 Voyage 做法, 每条音源是一条独立线路,
 * 用户手动挑选, 选中后当前歌曲立即用新线路重取。
 */
public final class LineDialog {

    public interface OnPick {
        void onPick(String lineName);
    }

    private LineDialog() {
    }

    public static void show(Activity activity, OnPick onPick) {
        MusicPlayer p = MusicPlayer.get();
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.bg_music_sheet);
        int pad = (int) (activity.getResources().getDisplayMetrics().density * 20);
        box.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(activity);
        title.setText("选择线路");
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setPadding(0, 0, 0, pad / 2);
        box.addView(title);

        TextView hint = new TextView(activity);
        hint.setText("每条线路独立取链，失败不会自动跳线");
        hint.setTextColor(0x88FFFFFF);
        hint.setTextSize(12);
        hint.setPadding(0, 0, 0, pad);
        box.addView(hint);

        final AlertDialog[] holder = new AlertDialog[1];

        for (int i = 0; i < MusicDirect.NAMES.length; i++) {
            final int idx = i;
            boolean sel = i == p.line();

            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(pad / 2, pad / 2, pad / 2, pad / 2);
            row.setBackgroundColor(sel ? 0x227AA8FF : Color.TRANSPARENT);

            TextView nm = new TextView(activity);
            nm.setText(MusicDirect.NAMES[i]);
            nm.setTextColor(sel ? 0xFF7AA8FF : Color.WHITE);
            nm.setTextSize(16);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(nm, lp);

            TextView desc = new TextView(activity);
            desc.setText(MusicDirect.DESCS[i] + (sel ? " · 当前" : ""));
            desc.setTextColor(0x99FFFFFF);
            desc.setTextSize(12);
            row.addView(desc);

            row.setOnClickListener(v -> {
                String name = p.setLine(idx);
                if (holder[0] != null) holder[0].dismiss();
                if (onPick != null) onPick.onPick(name);
            });
            box.addView(row);

            if (i < MusicDirect.NAMES.length - 1) {
                View div = new View(activity);
                div.setBackgroundColor(0x1AFFFFFF);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1);
                dlp.topMargin = pad / 2;
                dlp.bottomMargin = pad / 2;
                box.addView(div, dlp);
            }
        }

        AlertDialog dlg = new AlertDialog.Builder(activity)
                .setView(box)
                .create();
        holder[0] = dlg;
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dlg.getWindow().setGravity(Gravity.CENTER);
        }
        dlg.show();
    }
}
