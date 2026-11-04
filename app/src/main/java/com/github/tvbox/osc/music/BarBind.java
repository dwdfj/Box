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
    private final TextView name, info;
    /** v27.25: 下一曲从文字按钮换成矢量图标 ImageView。 */
    private final View nextBtn;
    private final BlobPlayButton blob;
    private final CoverRingView ring;
    /** v27.24: 灵动条上的音质胶囊(播放时显示当前档位, 由页面挂点击弹层)。 */
    private TextView qualityChip;
    private ObjectAnimator spin;

    public void setQualityChip(TextView chip) {
        this.qualityChip = chip;
        if (chip != null) {
            chip.setText(MusicPlayer.qualityLabel(MusicPlayer.get().preferredQuality()));
        }
    }

    public BarBind(LinearLayout bar, ImageView cover, TextView name, TextView info,
                   BlobPlayButton blob, View nextBtn) {
        this(bar, cover, name, info, blob, nextBtn, null);
    }

    public BarBind(LinearLayout bar, ImageView cover, TextView name, TextView info,
                   BlobPlayButton blob, View nextBtn, CoverRingView ring) {
        this.bar = bar;
        this.cover = cover;
        this.name = name;
        this.info = info;
        this.blob = blob;
        this.nextBtn = nextBtn;
        this.ring = ring;
    }

    /** v27.13: 500ms 同时推进封面环与 Blob 环形进度。 */
    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            if (MusicPlayer.get().isPlaying()) {
                float r = MusicPlayer.get().progressRatio();
                if (r >= 0) {
                    if (ring != null) ring.setProgress(r);
                    if (blob != null) blob.setProgress(r);
                }
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
            // v27.26: 原来只处理了「暂停」分支, 从影视页回到音乐页时歌在放、播放键却还是 ▶。
            // 这里按真实播放状态同步, 返回页面(onResume)也走同一套逻辑。
            syncPlayState();
        }
        bar.removeCallbacks(progressTick);
        bar.post(progressTick);
    }

    /** 用播放器的真实状态刷新播放键/封面旋转 —— 页面每次回到前台都调用。 */
    public void syncPlayState() {
        MusicPlayer p = MusicPlayer.get();
        if (nextBtn != null) nextBtn.setVisibility(p.hasNext() ? View.VISIBLE : View.GONE);
        if (p.isPlaying()) startSpin();
        else onPaused();
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
        // v27.24: 音质胶囊跟着实际取链档位走(取不到就显示当前偏好档)
        if (qualityChip != null) {
            String q = (quality == null || quality.isEmpty())
                    ? MusicPlayer.get().preferredQuality() : quality;
            qualityChip.setText(MusicPlayer.qualityLabel(q));
        }
        // v27.16: 没有封面(或图 404)时铺一张「歌名首字渐变图」, 不再留黑块
        android.graphics.drawable.Drawable fallback =
                new android.graphics.drawable.BitmapDrawable(
                        bar.getResources(), CoverFallback.render(song.name, 256));
        if (song.cover != null && !song.cover.isEmpty()) {
            Glide.with(bar.getContext()).load(song.cover)
                    .placeholder(fallback).error(fallback).into(cover);
        } else {
            cover.setImageDrawable(fallback);
        }
    }
}
