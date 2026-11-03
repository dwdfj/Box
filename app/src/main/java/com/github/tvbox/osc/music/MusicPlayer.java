package com.github.tvbox.osc.music;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * 小贾影视仓 v27.3: 音乐播放单例 —— 音乐厅/歌单详情两页共用。
 * 持有 LxRuntime(洛雪源) 与 ExoPlayer; 取链失败自动降音质重试。
 */
public class MusicPlayer {
    private static final String TAG = "MusicPlayer";
    private static final String[] QUALITY_FALLBACK = {"flac", "320k", "128k"};

    public interface Listener {
        void onStatus(String text);

        void onPlaying(MusicApi.Song song, String sourceName, String quality);

        void onPaused();

        void onError(String text);
    }

    private static volatile MusicPlayer sInstance;

    public static MusicPlayer get() {
        if (sInstance == null) {
            synchronized (MusicPlayer.class) {
                if (sInstance == null) sInstance = new MusicPlayer();
            }
        }
        return sInstance;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private LxRuntime lx;
    private ExoPlayer player;
    private final List<String> sourceKeys = new ArrayList<>();
    private final List<String> readyKeys = new ArrayList<>();
    private int sourceIdx = 0;
    private int qualityIdx = 0;
    private MusicApi.Song current;
    private final java.util.concurrent.CopyOnWriteArrayList<Listener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private boolean loading = false;
    private boolean sourcesReady = false;

    /** v27.8: 多监听器 —— 此前单槽位被 BarBind 独占, 页面收不到任何状态/错误(点击无反馈根因)。 */
    public void addListener(Listener l) {
        if (l != null) listeners.addIfAbsent(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public LxRuntime runtime() {
        if (lx == null) {
            lx = new LxRuntime((key, event, json) -> {
                if (LxRuntime.EVT_INIT.equals(event)) {
                    // v27.5 修复: 之前不看 status, 加载失败也标"音源就绪", 用户点歌全被吞。
                    // 任一源成功即视为可用; 全部失败才报错。
                    boolean ok = json != null && json.contains("\"status\":true");
                    if (ok) readyKeys.add(key);
                    sourcesReady = !readyKeys.isEmpty();
                    if (readyKeys.size() == 1 && ok) notifyStatus("音源就绪");
                    if (!sourcesReady) notifyStatus("音源加载失败: " + json);
                } else if (LxRuntime.EVT_UPDATE_ALERT.equals(event)) {
                    notifyStatus("音源提示更新");
                }
            });
            String[] files = {"animemusic.js", "helloworld.js", "hyw.js"};
            for (String f : files) {
                try {
                    String script = readAsset("lx_music/sources/" + f);
                    String key = f.replace(".js", "");
                    lx.loadSource(key, f.replace(".js", ""), script);
                    sourceKeys.add(key);
                } catch (Throwable t) {
                    Log.e(TAG, "load " + f, t);
                }
            }
        }
        return lx;
    }

    private String readAsset(String path) throws Throwable {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        java.io.InputStream is = AppContextHolder.get().getAssets().open(path);
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toString("UTF-8");
    }

    public boolean sourcesReady() {
        return sourcesReady;
    }

    public String currentSourceName() {
        String k = currentSourceKey();
        return k == null ? "" : runtime().getSources().get(k);
    }

    public String currentSourceKey() {
        // 只在加载成功的源里轮换(v27.5: 失败源不再参与取链)
        List<String> pool = readyKeys.isEmpty() ? sourceKeys : readyKeys;
        return pool.isEmpty() ? null : pool.get(sourceIdx % pool.size());
    }

    /** 轮换音源, 返回新源名。 */
    public String nextSource() {
        List<String> pool = readyKeys.isEmpty() ? sourceKeys : readyKeys;
        if (pool.size() > 1) {
            sourceIdx = (sourceIdx + 1) % pool.size();
            qualityIdx = 0;
        }
        return currentSourceName();
    }

    public MusicApi.Song current() {
        return current;
    }

    public boolean isPlaying() {
        return player != null && player.isPlaying();
    }

    /** 播放进度 0~1; 无媒体返回 -1(BarBind 驱动细进度条用)。 */
    public float progressRatio() {
        if (player == null || player.getDuration() <= 0) return -1f;
        return (float) player.getCurrentPosition() / player.getDuration();
    }

    public void toggle() {
        if (player == null) return;
        if (player.isPlaying()) {
            player.pause();
            notifyPaused();
        } else {
            player.play();
            if (current != null)
                notifyPlaying(current, currentSourceName(), QUALITY_FALLBACK[qualityIdx]);
        }
    }

    public void play(final MusicApi.Song song) {
        if (loading) {
            notifyStatus("正在取链，请稍候…");
            return;
        }
        loading = true;
        qualityIdx = 0;
        runtime(); // v27.9 修复: 必须先构造 runtime(装载音源), 否则 sourceKeys 为空 → 恒报"无可用音源"
        attempt(song, true, 0);
    }

    private void attempt(final MusicApi.Song song, final boolean allowFallback) {
        attempt(song, allowFallback, 0);
    }

    private void attempt(final MusicApi.Song song, final boolean allowFallback, final int waitCnt) {
        // v27.9: runtime() 里 loadSource 是 post 到 JS 线程的, env 未装好前等待重试(最多 ~7s)
        if (runtime().getSources().isEmpty()) {
            if (waitCnt < 24) {
                notifyStatus("音源启动中…(" + waitCnt + ")");
                main.postDelayed(() -> attempt(song, allowFallback, waitCnt + 1), 300);
                return;
            }
            loading = false;
            notifyError("音源加载失败，请点「源」切换重试");
            return;
        }
        final String key = currentSourceKey();
        if (key == null) {
            loading = false;
            notifyError("无可用音源");
            return;
        }
        // 单个源脚本还没 evaluate 完: 等它而不是立刻报错
        if (!runtime().getSources().containsKey(key)) {
            if (waitCnt < 24) {
                notifyStatus("音源装载中…");
                main.postDelayed(() -> attempt(song, allowFallback, waitCnt + 1), 300);
                return;
            }
            loading = false;
            notifyError("音源未就绪，请稍后重试");
            return;
        }
        notifyStatus("取链中(" + currentSourceName() + " " + QUALITY_FALLBACK[qualityIdx] + ")…");
        runtime().getMusicUrl(key, "wy", MusicApi.musicInfoFor(song), QUALITY_FALLBACK[qualityIdx],
                (srcKey, error, url, ekey, format) -> {
                    loading = false;
                    if (error != null || TextUtils.isEmpty(url)) {
                        if (allowFallback && qualityIdx < QUALITY_FALLBACK.length - 1) {
                            qualityIdx++;
                            loading = true;
                            attempt(song, true);
                        } else {
                            notifyError("取链失败: " + (error == null ? "空地址" : error) + "，可切「音源」重试");
                        }
                        return;
                    }
                    startPlay(song, url);
                });
    }

    private void startPlay(MusicApi.Song song, String url) {
        try {
            if (player == null) {
                // v27.5: 带 UA 的 HttpDataSource(很多音乐直链校验 UA, 默认 ExoPlayerLib UA 会被 403)
                androidx.media3.datasource.DefaultHttpDataSource.Factory httpFactory =
                        new androidx.media3.datasource.DefaultHttpDataSource.Factory()
                                .setUserAgent("Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) "
                                        + "Chrome/120.0.0.0 Mobile Safari/537.36")
                                .setConnectTimeoutMs(10000)
                                .setReadTimeoutMs(20000)
                                .setAllowCrossProtocolRedirects(true);
                player = new ExoPlayer.Builder(AppContextHolder.get())
                        .setMediaSourceFactory(new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(httpFactory))
                        .build();
                // v27.5: 异步播放错误(403/解码失败等)此前完全静默
                player.addListener(new androidx.media3.common.Player.Listener() {
                    @Override
                    public void onPlayerError(androidx.media3.common.PlaybackException error) {
                        notifyError("播放出错: code=" + error.errorCode + " " + error.getMessage());
                    }
                });
            }
            current = song;
            player.setMediaItem(MediaItem.fromUri(Uri.parse(url)));
            player.prepare();
            player.play();
            notifyStatus("播放中 · " + currentSourceName() + " · " + QUALITY_FALLBACK[qualityIdx]);
            notifyPlaying(song, currentSourceName(), QUALITY_FALLBACK[qualityIdx]);
        } catch (Throwable t) {
            notifyError("播放失败: " + t.getMessage());
        }
    }

    private void notifyStatus(String s) {
        Log.d(TAG, s);
        for (Listener l : listeners) l.onStatus(s);
    }

    private void notifyError(String s) {
        Log.w(TAG, s);
        for (Listener l : listeners) l.onError(s);
    }

    private void notifyPlaying(MusicApi.Song song, String sourceName, String quality) {
        for (Listener l : listeners) l.onPlaying(song, sourceName, quality);
    }

    private void notifyPaused() {
        for (Listener l : listeners) l.onPaused();
    }
}
