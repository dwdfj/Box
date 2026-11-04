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
    /** v27.16: 模式图标下的标签(图标只放符号, 模式名放这里)。 */
    private TextView modeLabel;
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
        modeLabel = findViewById(R.id.musicNowModeLabel);
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

        // 播放模式(v27.16: 图标 + 下方标签, 标签才是模式名)
        modeView.setOnClickListener(v -> {
            String m = p.cycleRepeatMode();
            if (modeLabel != null) modeLabel.setText(m);
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
            favView.setText(fav ? "♥" : "♡");
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
                        downloadView.setText("✓");
                        downloadView.setEnabled(true);
                        statusView.setText("已保存: " + path);
                    });
                }

                @Override
                public void onFail(String err) {
                    ui(() -> {
                        downloadView.setText("⇩");
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

        // 音质切换(v27.18: 弹层选档 —— 中文档位名 + 说明, 含母带; 选完立即用新档重取当前曲)
        qualityView.setOnClickListener(v ->
                com.github.tvbox.osc.music.MusicQualityDialog.show(this, () -> {
                    String q = p.preferredQuality();
                    qualityView.setText(MusicPlayer.qualityLabel(q));
                    MusicApi.Song cur = p.current();
                    if (cur != null) {
                        p.playQueue(p.queue(), Math.max(0, p.queue().indexOf(cur)));
                        statusView.setText("音质切至 " + MusicPlayer.qualityLabel(q) + "，正在重取");
                    } else {
                        statusView.setText("音质偏好已切至 " + MusicPlayer.qualityLabel(q));
                    }
                }));

        refreshModeAndFav();
        lineView.setText(p.lineName());
        qualityView.setText(MusicPlayer.qualityLabel(p.preferredQuality()));

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
        if (modeLabel != null) modeLabel.setText(MusicPlayer.MODE_NAMES[p.repeatMode()]);
        if (favView != null) {
            MusicApi.Song cur = p.current();
            favView.setText(cur != null && p.isFavorite(cur) ? "♥" : "♡");
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
        // v27.24: q 为空时不要留成空白胶囊 —— 退回当前偏好档(用户至少能看到"FLAC/320K")
        String shownQ = (q == null || q.isEmpty()) ? MusicPlayer.get().preferredQuality() : q;
        qualityView.setText(MusicPlayer.qualityLabel(shownQ));
        if (src != null && !src.isEmpty()) lineView.setText(src);
        // v27.16: 无论有没有封面都画 —— 没封面时用歌名首字渐变图, 不留空盘
        loadDisc(s.cover, s.name);
    }

    /** v27.15: 圆形旋转封面要 Bitmap。v27.16: 拿不到原图时给首字渐变图兜底。 */
    private void loadDisc(String url, String name) {
        if (disc == null) return;
        if (url == null || url.isEmpty()) {
            disc.setBitmap(com.github.tvbox.osc.music.CoverFallback.render(name, 512));
            return;
        }
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
                    public void onLoadFailed(android.graphics.drawable.Drawable errorDrawable) {
                        disc.setBitmap(com.github.tvbox.osc.music.CoverFallback.render(name, 512));
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
