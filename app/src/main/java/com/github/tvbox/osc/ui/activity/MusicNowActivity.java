package com.github.tvbox.osc.ui.activity;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.music.BlobPlayButton;
import com.github.tvbox.osc.music.LrcParser;
import com.github.tvbox.osc.music.LyricView;
import com.github.tvbox.osc.music.MusicApi;
import com.github.tvbox.osc.music.MusicDirect;
import com.github.tvbox.osc.music.MusicDownloader;
import com.github.tvbox.osc.music.MusicPlayer;

import java.util.List;

/**
 * 小贾影视仓 v27.13: 全屏播放页(Voyage 式) ——
 * 黑胶唱片 ⇄ 滚动歌词(点一下切换) + 歌名/歌手 + 可拖进度条 +
 * 模式/上一曲/Blob 播放/下一曲/队列 + 收藏/下载/线路。
 */
public class MusicNowActivity extends BaseActivity {
    private TextView nameView, singerView, qualityView, posView, durView, statusView;
    private TextView lineView, modeView, favView, downloadView;
    private SeekBar progress;
    private com.github.tvbox.osc.music.RotatingCoverView disc;
    private View discArea;
    private LyricView lyricView;
    private BlobPlayButton blob;

    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean ticking = false;
    private boolean lyricMode = false;
    private boolean dragging = false;

    /** 统一切主线程改 UI —— 下载/取链回调都在后台线程, 直接 setText 会崩。 */
    private void ui(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else main.post(r);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            MusicPlayer p = MusicPlayer.get();
            long dur = p.durationMs();
            long pos = p.positionMs();
            if (dur > 0) {
                if (!dragging) progress.setProgress((int) (pos * 1000 / dur));
                posView.setText(fmt(pos));
                durView.setText(fmt(dur));
                blob.setProgress((float) pos / dur);
            }
            blob.setPlaying(p.isPlaying());
            if (disc != null) disc.setPlaying(p.isPlaying()); // v27.15: 播放时封面转, 暂停停住
            if (lyricMode) lyricView.setProgress(pos);
            main.postDelayed(this, 300);
        }
    };

    private final MusicPlayer.Listener listener = new MusicPlayer.Listener() {
        @Override public void onStatus(String s) { statusView.setText(s); }

        @Override
        public void onPlaying(MusicApi.Song s, String src, String q) {
            bind(s, src, q);
            refreshModeAndFav();
        }

        @Override public void onPaused() { blob.setPlaying(false); }

        @Override public void onError(String s) { statusView.setText(s); }

        @Override
        public void onLyric(List<LrcParser.Line> lines) {
            lyricView.setLines(lines);
            statusView.setText(lines.isEmpty() ? "暂无歌词" : lines.size() + " 行歌词");
        }
    };

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_music_now;
    }

    @Override
    protected boolean musicFullscreen() {
        return true;
    }

    @Override
    protected void init() {
        int statusH = MusicActivity.sysBarHeight(this, true);
        int navH = MusicActivity.sysBarHeight(this, false);
        android.widget.LinearLayout root = findViewById(R.id.musicNowRoot);
        if (root != null) root.setPadding(root.getPaddingLeft(), statusH,
                root.getPaddingRight(), navH);

        MusicPlayer p = MusicPlayer.get();
        p.addListener(listener);

        nameView = findViewById(R.id.musicNowName);
        singerView = findViewById(R.id.musicNowSinger);
        qualityView = findViewById(R.id.musicNowQuality);
        posView = findViewById(R.id.musicNowPos);
        durView = findViewById(R.id.musicNowDur);
        statusView = findViewById(R.id.musicNowStatus);
        lineView = findViewById(R.id.musicNowLine);
        modeView = findViewById(R.id.musicNowMode);
        favView = findViewById(R.id.musicNowFav);
        downloadView = findViewById(R.id.musicNowDownload);
        progress = findViewById(R.id.musicNowProgress);
        disc = findViewById(R.id.musicDiscRing);
        discArea = findViewById(R.id.musicDiscArea);
        lyricView = findViewById(R.id.musicLyric);
        blob = findViewById(R.id.musicBlobPlay);

        findViewById(R.id.musicNowBack).setOnClickListener(v -> finish());
        blob.setOnClickListener(v -> p.toggle());
        findViewById(R.id.musicNowNext).setOnClickListener(v -> p.playNext());
        findViewById(R.id.musicNowPrev).setOnClickListener(v -> p.playPrev());

        // 唱片/歌词切换
        discArea.setOnClickListener(v -> toggleLyric(true));
        lyricView.setOnClickListener(v -> toggleLyric(false));
        lyricView.setSeekListener(ms -> {
            p.seekTo(ms);
            lyricView.setProgress(ms);
            statusView.setText("已跳转到 " + fmt(ms));
        });

        // 进度拖动
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int value, boolean fromUser) {
                if (fromUser) {
                    long dur = p.durationMs();
                    posView.setText(fmt(dur * value / 1000));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                dragging = false;
                long dur = p.durationMs();
                if (dur > 0) p.seekTo(dur * sb.getProgress() / 1000);
            }
        });

        // 播放模式
        modeView.setOnClickListener(v -> {
            String m = p.cycleRepeatMode();
            modeView.setText(m);
            statusView.setText("播放模式: " + m);
        });

        // 队列弹层
        findViewById(R.id.musicNowQueue).setOnClickListener(v ->
                QueueDialog.show(this, music -> {
                    // 选中即跳播
                    p.playQueue(p.queue(), p.queue().indexOf(music));
                }));

        // 收藏
        favView.setOnClickListener(v -> {
            MusicApi.Song cur = p.current();
            if (cur == null) return;
            boolean fav = p.toggleFavorite(cur);
            favView.setText(fav ? "♥ 已收藏" : "♡ 收藏");
            statusView.setText(fav ? "已加入收藏" : "已取消收藏");
        });

        // 下载
        downloadView.setOnClickListener(v -> {
            MusicApi.Song cur = p.current();
            if (cur == null) return;
            downloadView.setText("⇩ 0%");
            downloadView.setEnabled(false);
            MusicDownloader.download(cur, p.preferredQuality(), new MusicDownloader.Cb() {
                @Override
                public void onProgress(float f) {
                    // v27.15: 回调在下载线程, 必须切回主线程才能碰 View(否则 CalledFromWrongThread 闪退)
                    ui(() -> downloadView.setText("⇩ " + (int) (f * 100) + "%"));
                }

                @Override
                public void onDone(String path) {
                    ui(() -> {
                        downloadView.setText("✓ 已下载");
                        downloadView.setEnabled(true);
                        statusView.setText("已保存: " + path);
                    });
                }

                @Override
                public void onFail(String err) {
                    ui(() -> {
                        downloadView.setText("⇩ 下载");
                        downloadView.setEnabled(true);
                        statusView.setText("下载失败: " + err);
                    });
                }
            });
        });

        // 换线路
        findViewById(R.id.musicNowLineBtn).setOnClickListener(v -> {
            String name = p.nextLine();
            lineView.setText(name);
            statusView.setText("线路已切到 " + name + "，正在用新线路重取…");
        });

        // 音质
        qualityView.setOnClickListener(v -> {
            String q = p.cycleQuality();
            qualityView.setText(MusicPlayer.qualityLabel(q));
            statusView.setText("音质偏好已切至 " + MusicPlayer.qualityLabel(q) + "，下次点歌生效");
        });

        refreshModeAndFav();
        lineView.setText(p.lineName());
        qualityView.setText(MusicPlayer.qualityLabel(p.preferredQuality()));
        modeView.setText(MusicPlayer.MODE_NAMES[p.repeatMode()]);

        MusicApi.Song cur = p.current();
        if (cur != null) {
            bind(cur, null, p.preferredQuality());
            if (!p.lyric().isEmpty()) lyricView.setLines(p.lyric());
            statusView.setText(p.isPlaying() ? "播放中" : "已暂停");
        } else {
            statusView.setText("还没有播放任务 —— 返回列表点一首歌");
        }
    }

    private void refreshModeAndFav() {
        MusicPlayer p = MusicPlayer.get();
        if (modeView != null) modeView.setText(MusicPlayer.MODE_NAMES[p.repeatMode()]);
        if (favView != null) {
            MusicApi.Song cur = p.current();
            favView.setText(cur != null && p.isFavorite(cur) ? "♥ 已收藏" : "♡ 收藏");
        }
    }

    /** 唱片/歌词互斥显示; showLyric=true 进歌词, false 回唱片。 */
    private void toggleLyric(boolean showLyric) {
        if (lyricMode == showLyric) return;
        lyricMode = showLyric;
        discArea.setVisibility(showLyric ? View.GONE : View.VISIBLE);
        lyricView.setVisibility(showLyric ? View.VISIBLE : View.GONE);
        if (showLyric) {
            lyricView.setLines(MusicPlayer.get().lyric());
            lyricView.setProgress(MusicPlayer.get().positionMs());
            statusView.setText(MusicPlayer.get().lyric().isEmpty() ? "暂无歌词" : "轻点歌词可跳转");
        }
    }

    private void bind(MusicApi.Song s, String src, String q) {
        nameView.setText(s.name);
        singerView.setText(s.singer + (s.album == null || s.album.isEmpty() ? "" : " · " + s.album));
        qualityView.setText(q == null ? "" : MusicPlayer.qualityLabel(q));
        if (src != null && !src.isEmpty()) lineView.setText(src);
        if (s.cover != null && !s.cover.isEmpty()) {
            loadDisc(s.cover);
        }
    }

    /** v27.15: 圆形旋转封面要 Bitmap(拿不到原图就给黑胶空盘)。 */
    private void loadDisc(String url) {
        if (disc == null) return;
        Glide.with(mContext)
                .asBitmap()
                .load(url)
                .into(new com.bumptech.glide.request.target.CustomTarget<android.graphics.Bitmap>() {
                    @Override
                    public void onResourceReady(@androidx.annotation.NonNull android.graphics.Bitmap resource,
                                                com.bumptech.glide.request.transition.Transition<? super android.graphics.Bitmap> transition) {
                        disc.setBitmap(resource);
                    }

                    @Override
                    public void onLoadCleared(android.graphics.drawable.Drawable placeholder) {
                        disc.setBitmap(null);
                    }
                });
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
        long sec = Math.max(0, ms) / 1000;
        return String.format("%02d:%02d", sec / 60, sec % 60);
    }
}
