package com.github.tvbox.osc.ui.activity;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.music.BlobPlayButton;
import com.github.tvbox.osc.music.MusicApi;
import com.github.tvbox.osc.music.MusicPlayer;

/**
 * 小贾影视仓 v27.12: 全屏播放页(Now Playing) ——
 * 大封面 + 歌名/歌手置顶 + 进度条/时间 + 上一首/Blob 播放/下一首 + 音质/来源。
 */
public class MusicNowActivity extends BaseActivity {
    private TextView nameView, singerView, qualityView, posView, durView, statusView;
    private ProgressBar progress;
    private ImageView cover;
    private BlobPlayButton blob;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean ticking = false;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            MusicPlayer p = MusicPlayer.get();
            long dur = p.durationMs();
            long pos = p.positionMs();
            if (dur > 0) {
                progress.setProgress((int) (pos * 1000 / dur));
                posView.setText(fmt(pos));
                durView.setText(fmt(dur));
                blob.setProgress(dur > 0 ? (float) pos / dur : 0f);
            }
            blob.setPlaying(p.isPlaying());
            main.postDelayed(this, 500);
        }
    };

    private final MusicPlayer.Listener listener = new MusicPlayer.Listener() {
        @Override public void onStatus(String s) { statusView.setText(s); }
        @Override public void onPlaying(MusicApi.Song s, String src, String q) { bind(s, src, q); }
        @Override public void onPaused() { blob.setPlaying(false); }
        @Override public void onError(String s) { statusView.setText(s); }
    };

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_music_now;
    }

    @Override
    protected void init() {
        MusicPlayer.get().addListener(listener);
        nameView = findViewById(R.id.musicNowName);
        singerView = findViewById(R.id.musicNowSinger);
        qualityView = findViewById(R.id.musicNowQuality);
        posView = findViewById(R.id.musicNowPos);
        durView = findViewById(R.id.musicNowDur);
        statusView = findViewById(R.id.musicNowStatus);
        progress = findViewById(R.id.musicNowProgress);
        cover = findViewById(R.id.musicNowCover);
        blob = findViewById(R.id.musicBlobPlay);

        findViewById(R.id.musicNowBack).setOnClickListener(v -> finish());
        blob.setOnClickListener(v -> MusicPlayer.get().toggle());
        findViewById(R.id.musicNowNext).setOnClickListener(v -> MusicPlayer.get().playNext());
        findViewById(R.id.musicNowPrev).setOnClickListener(v -> MusicPlayer.get().playPrev());

        MusicPlayer p = MusicPlayer.get();
        MusicApi.Song cur = p.current();
        if (cur != null) {
            bind(cur, null, p.preferredQuality());
            statusView.setText(p.isPlaying() ? "播放中" : "已暂停");
        } else {
            statusView.setText("还没有播放任务 —— 返回列表点一首歌");
        }
    }

    private void bind(MusicApi.Song s, String src, String q) {
        nameView.setText(s.name);
        singerView.setText(s.singer + (s.album == null || s.album.isEmpty() ? "" : " · " + s.album));
        qualityView.setText(q == null ? "" : q);
        if (s.cover != null && !s.cover.isEmpty()) {
            Glide.with(mContext).load(s.cover).into(cover);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!ticking) {
            ticking = true;
            main.post(tick);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        ticking = false;
        main.removeCallbacks(tick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        MusicPlayer.get().removeListener(listener);
    }

    private static String fmt(long ms) {
        long sec = ms / 1000;
        return String.format("%02d:%02d", sec / 60, sec % 60);
    }
}
