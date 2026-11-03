package com.github.tvbox.osc.ui.activity;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.music.MusicApi;
import com.github.tvbox.osc.music.MusicPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * 小贾影视仓 v27.13: 播放队列弹层(BottomSheet 观感) ——
 * 当前曲高亮显示, 点击任意行跳播。
 */
public final class QueueDialog {

    public interface OnPick {
        void onPick(MusicApi.Song song);
    }

    private QueueDialog() {
    }

    public static void show(Activity activity, OnPick onPick) {
        MusicPlayer p = MusicPlayer.get();
        List<MusicApi.Song> items = p.queue();
        View content = LayoutInflater.from(activity).inflate(R.layout.dialog_music_queue, null);
        RecyclerView rv = content.findViewById(R.id.queueList);
        TextView title = content.findViewById(R.id.queueTitle);
        title.setText("播放队列 · " + items.size() + " 首");

        PopupWindow popup = new PopupWindow(content,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.min(activity.getResources().getDisplayMetrics().heightPixels * 3 / 5,
                        dp(activity, 460)),
                true);
        popup.setBackgroundDrawable(new ColorDrawable(0x99000000));
        popup.setOutsideTouchable(true);

        rv.setLayoutManager(new LinearLayoutManager(activity));
        int curPos = p.queuePos();
        rv.setAdapter(new QueueAdapter(items, curPos, song -> {
            popup.dismiss();
            if (onPick != null) onPick.onPick(song);
        }));

        content.findViewById(R.id.queueClose).setOnClickListener(v -> popup.dismiss());

        if (activity.getWindow() != null) {
            View decor = activity.getWindow().getDecorView();
            popup.showAtLocation(decor, Gravity.BOTTOM, 0, 0);
        }
    }

    private static int dp(Activity a, int v) {
        return (int) (a.getResources().getDisplayMetrics().density * v);
    }

    private static class QueueAdapter extends RecyclerView.Adapter<QueueAdapter.H> {
        private final List<MusicApi.Song> data = new ArrayList<>();
        private final int curPos;
        private final OnPick pick;

        QueueAdapter(List<MusicApi.Song> items, int curPos, OnPick pick) {
            if (items != null) data.addAll(items);
            this.curPos = curPos;
            this.pick = pick;
        }

        static class H extends RecyclerView.ViewHolder {
            TextView index, name, singer, dur;

            H(View v) {
                super(v);
                index = v.findViewById(R.id.qIndex);
                name = v.findViewById(R.id.qName);
                singer = v.findViewById(R.id.qSinger);
                dur = v.findViewById(R.id.qDur);
            }
        }

        @NonNull
        @Override
        public H onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new H(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_music_queue, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull H h, int position) {
            MusicApi.Song s = data.get(position);
            boolean now = position == curPos;
            h.index.setText(now ? "▶" : String.valueOf(position + 1));
            h.index.setTextColor(now ? 0xFF7AA8FF : 0x66FFFFFF);
            h.name.setText(s.name);
            h.name.setTextColor(now ? 0xFF7AA8FF : Color.WHITE);
            h.singer.setText(s.singer);
            h.singer.setTextColor(now ? 0xAA7AA8FF : 0x88FFFFFF);
            h.dur.setText(s.durationMs > 0 ? fmt(s.durationMs) : "");
            h.itemView.setBackgroundColor(now ? 0x227AA8FF : Color.TRANSPARENT);
            h.itemView.setOnClickListener(v -> {
                if (pick != null) pick.onPick(s);
            });
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        private static String fmt(long ms) {
            long sec = ms / 1000;
            return String.format("%02d:%02d", sec / 60, sec % 60);
        }
    }
}
