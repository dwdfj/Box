package com.github.tvbox.osc.music;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.github.tvbox.osc.R;

/**
 * 小贾影视仓 v27.12: 灵动条绑定(Mini Player) —— 封面 + 歌名/歌手 + Blob 液态播放钮(带环形进度) + 下一首。
 * 条体点击进全屏播放页; Blob 钮点击播放/暂停; 下一首切队列。
 */
public final class BarBind implements MusicPlayer.Listener {
    private final LinearLayout bar;
    private final ImageView cover;
    private final TextView name, info, nextBtn;
    private final BlobPlayButton blob;
    private ObjectAnimator spin;

    public BarBind(LinearLayout bar, ImageView cover, TextView name, TextView info,
                   BlobPlayButton blob, TextView nextBtn) {
        this.bar = bar;
        this.cover = cover;
        this.name = name;
        this.info = info;
        this.blob = blob;
        this.nextBtn = nextBtn;
    }

    /** v27.12: 500ms 刷新 Blob 环形进度。 */
    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            if (blob != null && MusicPlayer.get().isPlaying()) {
                float r = MusicPlayer.get().progressRatio();
                if (r >= 0) blob.setProgress(r);
            }
            bar.postDelayed(this, 500);
        }
    };

    public void bind(Activity activity) {
        MusicPlayer p = MusicPlayer.get();
        p.addListener(this);
        if (blob != null) blob.setOnClickListener(v -> p.toggle());
        if (nextBtn != null) {
            nextBtn.setOnClickListener(v -> {
                if (p.hasNext()) p.playNext();
            });
        }
        // 点播放条任意处进全屏播放页
        bar.setOnClickListener(v -> v.getContext().startActivity(
                new android.content.Intent(v.getContext(),
                        com.github.tvbox.osc.ui.activity.MusicNowActivity.class)));
        MusicApi.Song cur = p.current();
        if (cur != null) {
            show(cur, "", "");
            if (!p.isPlaying()) onPaused();
        }
        bar.removeCallbacks(progressTick);
        bar.post(progressTick);
    }

    /** 页面销毁时解除监听, 防止 Activity 泄漏。 */
    public void unbind() {
        MusicPlayer.get().removeListener(this);
        bar.removeCallbacks(progressTick);
        stopSpin(false);
    }

    @Override
    public void onStatus(String text) {
        // 状态由页面自身 status 行显示
    }

    @Override
    public void onPlaying(MusicApi.Song song, String sourceName, String quality) {
        show(song, sourceName, quality);
        startSpin();
        if (nextBtn != null) nextBtn.setVisibility(MusicPlayer.get().hasNext() ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onPaused() {
        if (blob != null) blob.setPlaying(false);
        stopSpin(false);
    }

    @Override
    public void onError(String text) {
        bar.setVisibility(View.VISIBLE);
        name.setText(text);
        if (blob != null) blob.setPlaying(false);
    }

    private void startSpin() {
        if (blob != null) blob.setPlaying(true);
        if (cover == null) return;
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
        StringBuilder sb = new StringBuilder(song.singer == null ? "" : song.singer);
        if (song.album != null && !song.album.isEmpty()) sb.append(" · ").append(song.album);
        info.setText(sb.toString());
        if (song.cover != null && !song.cover.isEmpty()) {
            Glide.with(bar.getContext()).load(song.cover).into(cover);
        }
    }
}
