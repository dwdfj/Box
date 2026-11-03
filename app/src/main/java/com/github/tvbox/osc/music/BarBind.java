package com.github.tvbox.osc.music;

import android.animation.ObjectAnimator;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.github.tvbox.osc.R;

/**
 * 小贾影视仓 v27.3: 底部播放条绑定 —— 音乐厅/歌单页共用同一份 MusicPlayer 状态。
 * v27.5: 播放中封面无限旋转(Voyage 式), 暂停即停; 细进度条 500ms 刷新。
 */
public final class BarBind implements MusicPlayer.Listener {
    private final LinearLayout bar;
    private final ImageView cover;
    private final TextView name, info, playPause;
    private final ProgressBar progress;
    private ObjectAnimator spin; // 封面旋转
    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            bar.postDelayed(this, 500);
        }
    };

    public BarBind(LinearLayout bar, ImageView cover, TextView name, TextView info, TextView playPause) {
        this(bar, cover, name, info, playPause, null);
    }

    public BarBind(LinearLayout bar, ImageView cover, TextView name, TextView info, TextView playPause,
                   ProgressBar progress) {
        this.bar = bar;
        this.cover = cover;
        this.name = name;
        this.info = info;
        this.playPause = playPause;
        this.progress = progress;
    }

    public void bind(android.app.Activity activity) {
        MusicPlayer p = MusicPlayer.get();
        p.setListener(this);
        playPause.setOnClickListener(v -> p.toggle());
        MusicApi.Song cur = p.current();
        if (cur != null) {
            show(cur, "", "");
            if (!p.isPlaying()) onPaused();
        }
    }

    @Override
    public void onStatus(String text) {
        // 状态由页面自身 status 行显示, 播放条不重复
    }

    @Override
    public void onPlaying(MusicApi.Song song, String sourceName, String quality) {
        show(song, sourceName, quality);
        startSpin();
        bar.removeCallbacks(progressTick);
        bar.post(progressTick);
        if (progress != null) progress.setVisibility(View.VISIBLE);
    }

    @Override
    public void onPaused() {
        playPause.setText("▶");
        stopSpin(false);
        bar.removeCallbacks(progressTick);
    }

    @Override
    public void onError(String text) {
        name.setText(text);
        stopSpin(false);
        bar.removeCallbacks(progressTick);
        if (progress != null) progress.setVisibility(View.GONE);
    }

    private void updateProgress() {
        if (progress == null) return;
        float r = MusicPlayer.get().progressRatio();
        if (r >= 0) progress.setProgress(Math.round(r * 1000f));
    }

    private void startSpin() {
        if (spin == null) {
            spin = ObjectAnimator.ofFloat(cover, View.ROTATION, 0f, 360f);
            spin.setDuration(12000);
            spin.setRepeatCount(ObjectAnimator.INFINITE);
            spin.setInterpolator(null);
        }
        if (!spin.isRunning()) spin.start();
    }

    private void stopSpin(boolean reset) {
        if (spin != null && spin.isRunning()) {
            spin.cancel();
            if (reset) cover.setRotation(0f);
        }
    }

    private void show(MusicApi.Song song, String sourceName, String quality) {
        bar.setVisibility(View.VISIBLE);
        name.setText(song.name);
        StringBuilder sb = new StringBuilder(song.singer);
        if (song.album != null && !song.album.isEmpty()) sb.append(" · ").append(song.album);
        info.setText(sb.toString());
        if (song.cover != null && !song.cover.isEmpty()) {
            Glide.with(bar.getContext()).load(song.cover).into(cover);
        }
        playPause.setText("⏸");
    }
}
