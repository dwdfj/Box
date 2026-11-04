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

        /** v27.13: 歌词就绪(可能为空列表 = 暂无歌词)。 */
        default void onLyric(java.util.List<LrcParser.Line> lines) {
        }
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
    private ExoPlayer player;
    private int qualityIdx = 0;
    private String lastQuality = "flac";
    private String lastSourceName;
    private MusicApi.Song current;
    private final java.util.concurrent.CopyOnWriteArrayList<Listener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private boolean loading = false;

    // ---- v27.12: 播放队列 + 最近播放 ----
    private final List<MusicApi.Song> queue = new ArrayList<>();
    private int queuePos = -1;
    private static final String RECENT_PREF = "xj_music_recent";
    private static final int RECENT_MAX = 50;

    // ---- v27.13: 线路(音源)自由挑选 + 播放模式 + 收藏/歌词 ----
    /** 播放模式: 单曲循环 / 列表循环 / 顺序 / 随机。 */
    public static final int MODE_SINGLE = 0;
    public static final int MODE_LOOP = 1;
    public static final int MODE_ORDER = 2;
    public static final int MODE_SHUFFLE = 3;
    public static final String[] MODE_NAMES = {"单曲循环", "列表循环", "顺序播放", "随机播放"};

    private static final String PREF = "xj_music_cfg";
    private int line = MusicDirect.LINE_LUOXUE;
    private int repeatMode = MODE_LOOP;
    private int preferQ = 0;
    private boolean lineInited = false;

    /** 当前线路 id。 */
    public int line() {
        initCfg();
        return line;
    }

    public String lineName() {
        return MusicDirect.NAMES[line()];
    }

    /** 切换线路, 返回新线路名。切换后当前歌曲会按新线路重取。 */
    public String setLine(int l) {
        initCfg();
        if (l < 0 || l >= MusicDirect.NAMES.length) return lineName();
        line = l;
        preferQ = 0; // 换线路后档位表变了, 回到该线路第一档
        saveCfg();
        MusicApi.Song cur = current;
        if (cur != null) {
            // 用户主动切线路 → 用新线路重取当前曲
            playAt(Math.max(0, queuePos));
        }
        return lineName();
    }

    /** 循环切到下一线路。 */
    public String nextLine() {
        initCfg();
        return setLine((line + 1) % MusicDirect.NAMES.length);
    }

    public int repeatMode() {
        initCfg();
        return repeatMode;
    }

    public String setRepeatMode(int m) {
        initCfg();
        if (m < 0 || m >= MODE_NAMES.length) return MODE_NAMES[repeatMode];
        repeatMode = m;
        saveCfg();
        return MODE_NAMES[repeatMode];
    }

    /** 循环切播放模式, 返回新名字。 */
    public String cycleRepeatMode() {
        initCfg();
        return setRepeatMode((repeatMode + 1) % MODE_NAMES.length);
    }

    private void initCfg() {
        if (lineInited) return;
        lineInited = true;
        try {
            android.content.SharedPreferences sp =
                    AppContextHolder.get().getSharedPreferences(PREF, 0);
            line = sp.getInt("line", MusicDirect.LINE_LUOXUE);
            repeatMode = sp.getInt("mode", MODE_LOOP);
            preferQ = sp.getInt("q", 0);
        } catch (Throwable ignored) {
        }
    }

    private void saveCfg() {
        try {
            AppContextHolder.get().getSharedPreferences(PREF, 0)
                    .edit().putInt("line", line).putInt("mode", repeatMode).putInt("q", preferQ).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 队列(供队列弹层用)。 */
    public List<MusicApi.Song> queue() {
        return new ArrayList<>(queue);
    }

    public int queuePos() {
        return queuePos;
    }

    /** 用整张列表建队列并从 start 开始播。 */
    public void playQueue(List<MusicApi.Song> songs, int start) {
        if (songs == null || songs.isEmpty()) return;
        queue.clear();
        queue.addAll(songs);
        queuePos = Math.max(0, Math.min(start, songs.size() - 1));
        playAt(queuePos);
    }

    private void playAt(int pos) {
        queuePos = pos;
        MusicApi.Song s = queue.get(pos);
        loading = true;
        qualityIdx = 0;
        ensureCover(s); // v27.24: 队列里的歌可能来自历史/洛雪, 没有封面 —— 播前补一次
        notifyStatus("已点「" + s.name + "」，取链中…");
        attemptDirect(s, 0);
    }

    /** v27.24: 封面兜底 —— 只补没有封面的曲目, 补到后刷新灵动条/播放页, 不打断播放。 */
    private void ensureCover(final MusicApi.Song s) {
        if (s == null || s.songmid == null) return;
        if (s.cover != null && !s.cover.isEmpty()) return;
        List<MusicApi.Song> one = new ArrayList<>();
        one.add(s);
        MusicApi.fillCovers(one, (err, data) -> {
            if (s.cover != null && !s.cover.isEmpty() && s == current) {
                notifyPlaying(current, lastSourceName == null ? lineName() : lastSourceName, lastQuality);
            }
        });
    }

    public boolean hasNext() {
        return queuePos + 1 < queue.size();
    }

    public boolean hasPrev() {
        return queuePos > 0;
    }

    public void playNext() {
        // 列表循环: 到尾回到第一首; 顺序: 到尾停
        if (hasNext()) playAt(queuePos + 1);
        else if (repeatMode() == MODE_LOOP && !queue.isEmpty()) playAt(0);
    }

    public void playPrev() {
        if (hasPrev()) playAt(queuePos - 1);
        else if (repeatMode() == MODE_LOOP && !queue.isEmpty()) playAt(queue.size() - 1);
    }

    /**
     * v27.13: 一首播完后的去向, 由播放模式决定。
     * 单曲循环 → 原曲重播(重新取链); 随机 → 随机一首; 列表循环/顺序 → playNext。
     */
    private void onTrackEnded() {
        initCfg();
        if (queue.isEmpty()) return;
        switch (repeatMode) {
            case MODE_SINGLE:
                playAt(queuePos);
                break;
            case MODE_SHUFFLE:
                if (queue.size() == 1) playAt(0);
                else {
                    int n;
                    do {
                        n = (int) (Math.random() * queue.size());
                    } while (n == queuePos);
                    playAt(n);
                }
                break;
            default:
                playNext();
                break;
        }
    }

    /** 最近播放(SharedPreferences, 去重, 最前为新)。 */
    public List<MusicApi.Song> recent() {
        List<MusicApi.Song> out = new ArrayList<>();
        try {
            org.json.JSONArray arr = new org.json.JSONArray(
                    AppContextHolder.get().getSharedPreferences(RECENT_PREF, 0)
                            .getString("list", "[]"));
            for (int i = 0; i < arr.length() && i < RECENT_MAX; i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                MusicApi.Song s = new MusicApi.Song();
                s.songmid = o.optString("id");
                s.name = o.optString("n");
                s.singer = o.optString("a");
                s.album = o.optString("al");
                s.albumId = o.optString("ai");
                s.cover = o.optString("c");
                if (s.songmid != null && !s.songmid.isEmpty()) out.add(s);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private void addRecent(MusicApi.Song song) {
        try {
            List<MusicApi.Song> list = recent();
            for (java.util.Iterator<MusicApi.Song> it = list.iterator(); it.hasNext(); ) {
                if (song.songmid != null && song.songmid.equals(it.next().songmid)) it.remove();
            }
            list.add(0, song);
            while (list.size() > RECENT_MAX) list.remove(list.size() - 1);
            org.json.JSONArray arr = new org.json.JSONArray();
            for (MusicApi.Song s : list) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("id", s.songmid == null ? "" : s.songmid);
                o.put("n", s.name == null ? "" : s.name);
                o.put("a", s.singer == null ? "" : s.singer);
                o.put("al", s.album == null ? "" : s.album);
                o.put("ai", s.albumId == null ? "" : s.albumId);
                o.put("c", s.cover == null ? "" : s.cover);
                arr.put(o);
            }
            AppContextHolder.get().getSharedPreferences(RECENT_PREF, 0)
                    .edit().putString("list", arr.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    /** v27.8: 多监听器 —— 此前单槽位被 BarBind 独占, 页面收不到任何状态/错误(点击无反馈根因)。 */
    public void addListener(Listener l) {
        if (l != null) listeners.addIfAbsent(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public MusicApi.Song current() {
        return current;
    }

    // ---- v27.13: 歌词 ----
    private java.util.List<LrcParser.Line> lyric = java.util.Collections.emptyList();
    private String lyricSongmid;

    public java.util.List<LrcParser.Line> lyric() {
        return lyric;
    }

    private void loadLyric(final MusicApi.Song song) {
        if (song == null || song.songmid == null) return;
        if (song.songmid.equals(lyricSongmid)) return; // 同一首不重复拉
        lyricSongmid = song.songmid;
        lyric = java.util.Collections.emptyList();
        notifyLyric(lyric);
        LyricApi.fetch(song.songmid, (lines, raw, err) -> {
            if (!song.songmid.equals(lyricSongmid)) return; // 已切歌
            lyric = lines == null ? java.util.Collections.emptyList() : lines;
            notifyLyric(lyric);
        });
    }

    private void notifyLyric(java.util.List<LrcParser.Line> lines) {
        for (Listener l : listeners) l.onLyric(lines);
    }

    // ---- v27.13: 收藏 ----
    private static final String FAV_PREF = "xj_music_fav";

    public boolean isFavorite(MusicApi.Song song) {
        if (song == null || song.songmid == null) return false;
        for (MusicApi.Song s : favorites()) {
            if (song.songmid.equals(s.songmid)) return true;
        }
        return false;
    }

    /** 切换收藏, 返回切换后是否已收藏。 */
    public boolean toggleFavorite(MusicApi.Song song) {
        List<MusicApi.Song> list = favorites();
        boolean now;
        for (java.util.Iterator<MusicApi.Song> it = list.iterator(); it.hasNext(); ) {
            if (song.songmid != null && song.songmid.equals(it.next().songmid)) {
                it.remove();
                saveSongs(FAV_PREF, list);
                return false;
            }
        }
        list.add(0, song);
        saveSongs(FAV_PREF, list);
        now = true;
        return now;
    }

    public List<MusicApi.Song> favorites() {
        return loadSongs(FAV_PREF);
    }

    private List<MusicApi.Song> loadSongs(String pref) {
        List<MusicApi.Song> out = new ArrayList<>();
        try {
            org.json.JSONArray arr = new org.json.JSONArray(
                    AppContextHolder.get().getSharedPreferences(pref, 0).getString("list", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                MusicApi.Song s = new MusicApi.Song();
                s.songmid = o.optString("id");
                s.name = o.optString("n");
                s.singer = o.optString("a");
                s.album = o.optString("al");
                s.albumId = o.optString("ai");
                s.cover = o.optString("c");
                if (s.songmid != null && !s.songmid.isEmpty()) out.add(s);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private void saveSongs(String pref, List<MusicApi.Song> list) {
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (MusicApi.Song s : list) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("id", s.songmid == null ? "" : s.songmid);
                o.put("n", s.name == null ? "" : s.name);
                o.put("a", s.singer == null ? "" : s.singer);
                o.put("al", s.album == null ? "" : s.album);
                o.put("ai", s.albumId == null ? "" : s.albumId);
                o.put("c", s.cover == null ? "" : s.cover);
                arr.put(o);
            }
            AppContextHolder.get().getSharedPreferences(pref, 0)
                    .edit().putString("list", arr.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    public boolean isPlaying() {
        return player != null && player.isPlaying();
    }

    /** 播放进度 0~1; 无媒体返回 -1(BarBind 驱动细进度条用)。 */
    public float progressRatio() {
        if (player == null || player.getDuration() <= 0) return -1f;
        return (float) player.getCurrentPosition() / player.getDuration();
    }

    /** v27.10: 全屏播放页用。 */
    public long positionMs() {
        return player == null ? 0 : Math.max(0, player.getCurrentPosition());
    }

    public long durationMs() {
        return player == null || player.getDuration() <= 0 ? 0 : player.getDuration();
    }

    /** v27.13: 拖动进度条/点歌词跳转。 */
    public void seekTo(long ms) {
        if (player != null) player.seekTo(Math.max(0, ms));
    }

    public void toggle() {
        if (player == null) return;
        if (player.isPlaying()) {
            player.pause();
            notifyPaused();
        } else {
            player.play();
            if (current != null)
                notifyPlaying(current, lastSourceName == null ? lineName() : lastSourceName, lastQuality);
        }
    }

    public void play(final MusicApi.Song song) {
        // v27.12: 单曲点播 = 单元素队列
        queue.clear();
        queue.add(song);
        queuePos = 0;
        playAt(0);
    }

    /**
     * v27.25: 实测后重排各线路档位(2026-11 逐档验证):
     *   洛雪 c.wwwweb.top  quality=master/flac/320k/128k 全部返回真地址(hires 会回落 flac);
     *   笒音 nianxinxz     level=hires/lossless 有地址, 320k/128k/flac/master 返回空 url;
     *   Voyage            flac/320k/hires/master/128k 全通。
     * 所以「母带」不再是 Voyage 独有 —— 洛雪也能选中, 用户在弹层里点得动才是关键。
     */
    private static final String[] Q_LUOXUE = {"flac", "master", "320k", "128k"};
    private static final String[] Q_CENYIN = {"lossless", "hires"};
    private static final String[] Q_VOYAGE = {"flac", "320k", "hires", "master", "128k"};

    /** 指定线路的档位表。 */
    private static String[] qualitiesOf(int line) {
        switch (line) {
            case MusicDirect.LINE_CENYIN:
                return Q_CENYIN;
            case MusicDirect.LINE_VOYAGE:
                return Q_VOYAGE;
            default:
                return Q_LUOXUE;
        }
    }

    /** 当前线路可选的音质档位(降档重试也走这张表)。 */
    private static String[] qualities() {
        return qualitiesOf(MusicPlayer.get().line());
    }

    /** v27.25: 哪条线路支持这个档位(-1 = 谁都不支持)。 */
    public int lineSupporting(String q) {
        for (int l = 0; l < MusicDirect.NAMES.length; l++) {
            for (String s : qualitiesOf(l)) {
                if (android.text.TextUtils.equals(s, q)) return l;
            }
        }
        return -1;
    }

    /**
     * v27.25: 选档位。当前线路支持就直接用; 不支持则自动切到支持的线路(例如在笒音下选「母带」→ 切洛雪),
     * 这样弹层里每一档都能点得动, 不会再出现「全灰 + 点不了」。
     * 返回 true 表示选档成功(调用方负责用新档重取当前曲)。
     */
    public boolean selectQuality(String q) {
        initCfg();
        String[] qs = qualities();
        for (int i = 0; i < qs.length; i++) {
            if (android.text.TextUtils.equals(qs[i], q)) {
                preferQ = i;
                saveCfg();
                return true;
            }
        }
        int alt = lineSupporting(q);
        if (alt >= 0) {
            line = alt;
            String[] aqs = qualitiesOf(alt);
            for (int i = 0; i < aqs.length; i++) {
                if (android.text.TextUtils.equals(aqs[i], q)) preferQ = i;
            }
            saveCfg();
            return true;
        }
        return false;
    }

    /** 循环切换音质偏好, 返回新档位(如 "master")。 */
    public String cycleQuality() {
        String[] qs = qualities();
        preferQ = (preferQ + 1) % qs.length;
        saveCfg();
        return qs[preferQ];
    }

    /** v27.18: 当前线路可选的音质档位 —— 音质弹层用它判断哪些档位置灰。 */
    public String[] availableQualities() {
        return qualities();
    }

    /** v27.18: 直接指定音质档位(线路不支持的档位忽略并返回 false)。 */
    public boolean setQuality(String q) {
        String[] qs = qualities();
        for (int i = 0; i < qs.length; i++) {
            if (android.text.TextUtils.equals(qs[i], q)) {
                preferQ = i;
                saveCfg();
                return true;
            }
        }
        return false;
    }

    /** 当前音质偏好; 换线路后档位表变了就收敛到该线路第一档。 */
    public String preferredQuality() {
        String[] qs = qualities();
        preferQ = Math.max(0, Math.min(preferQ, qs.length - 1));
        return qs[preferQ];
    }

    /** UI 展示用的音质名 —— master/hires 显示成中文, 其余原样。 */
    public static String qualityLabel(String q) {
        if (q == null) return "";
        if ("master".equals(q)) return "母带";
        if ("hires".equals(q)) return "Hi-Res";
        if ("lossless".equals(q)) return "无损";
        // v27.18: 320k/128k 统一大写, 顶栏胶囊别再出现小写数字串
        if ("320k".equals(q)) return "320K";
        if ("128k".equals(q)) return "128K";
        if ("flac".equals(q)) return "FLAC";
        return q;
    }

    private void attemptDirect(final MusicApi.Song song, final int i) {
        final String[] qs = qualities();
        if (i >= qs.length) {
            loading = false;
            notifyError("「" + lineName() + "」线路取链失败(" + android.text.TextUtils.join("/", qs)
                    + " 均无)，可点「线路」换一条");
            return;
        }
        final int qSlot = (preferQ + i) % qs.length;
        final String q = qs[qSlot];
        qualityIdx = Math.min(i, QUALITY_FALLBACK.length - 1);
        lastQuality = q;
        lastSourceName = lineName();
        notifyStatus("取链中(" + lineName() + " · " + qualityLabel(q) + ")…");
        // Voyage 官方接口需要 name/singer/albumId 参与上游匹配, 洛雪/笒音会忽略它们
        MusicDirect.fetch(line(), song.songmid, q, song.name, song.singer, song.albumId,
                (sourceName, url, err) -> {
            if (!loading) return; // 已被超时/新点击接管
            if (url != null && !TextUtils.isEmpty(url)) {
                lastSourceName = sourceName;
                startPlay(song, url);
                return;
            }
            // 笒音只有 lossless 一档, 降档没意义, 直接报错
            if (line() == MusicDirect.LINE_CENYIN) {
                loading = false;
                notifyError((err == null ? "笒音" : err) + " — 可切到「洛雪」或「Voyage」线路");
                return;
            }
            // v27.16: Voyage 在 MusicDirect.voyageTry 内部已经把 master→hires→flac→320k→128k
            // 全部试过一遍, 外层若再降档就是 5×5=25 次请求 —— 这正是"加载很慢"的主因。
            // 所以 Voyage 失败即直接报错, 不再重复降档。
            if (line() == MusicDirect.LINE_VOYAGE) {
                loading = false;
                notifyError("Voyage 该曲目各档位均无可用地址 — 可切到「洛雪」线路");
                return;
            }
            // 线路内降音质重试; 全档失败才报错(不自动跨线路 —— 用户选定哪条就是哪条)
            attemptDirect(song, i + 1);
        });
    }

    private void startPlay(MusicApi.Song song, String url) {
        try {
            loading = false; // v27.9: 取链结束, 恢复可再次点歌
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
                // v27.5: 异步播放错误(403/解码失败等)此前完全静默; v27.12: 播完自动下一首
                player.addListener(new androidx.media3.common.Player.Listener() {
                    @Override
                    public void onPlayerError(androidx.media3.common.PlaybackException error) {
                        notifyError("播放出错: code=" + error.errorCode + " " + error.getMessage());
                    }

                    @Override
                    public void onPlaybackStateChanged(int state) {
                        if (state == androidx.media3.common.Player.STATE_ENDED)
                            main.post(MusicPlayer.this::onTrackEnded);
                    }
                });
            }
            current = song;
            addRecent(song); // v27.12: 写入最近播放
            loadLyric(song); // v27.13: 拉歌词
            player.setMediaItem(MediaItem.fromUri(Uri.parse(url)));
            player.prepare();
            player.play();
            notifyStatus("播放中 · " + (lastSourceName == null ? lineName() : lastSourceName)
                    + " · " + qualityLabel(lastQuality));
            notifyPlaying(song, lastSourceName == null ? lineName() : lastSourceName, lastQuality);
        } catch (Throwable t) {
            loading = false;
            notifyError("播放失败: " + t.getMessage());
        }
    }

    /**
     * v27.15: 所有回调兜底切主线程 —— 取链回调来自 OkHttp 线程, 曾经直接 setText 崩过
     * (CalledFromWrongThread: Only the original thread that created a view hierarchy can touch its views)。
     * 监听器注册在 Activity 里, 一律保证在主线程被调用。
     */
    private void notifyStatus(final String s) {
        Log.d(TAG, s);
        main.post(() -> {
            for (Listener l : listeners) l.onStatus(s);
        });
    }

    private void notifyError(final String s) {
        Log.w(TAG, s);
        main.post(() -> {
            for (Listener l : listeners) l.onError(s);
        });
    }

    private void notifyPlaying(final MusicApi.Song song, final String sourceName, final String quality) {
        main.post(() -> {
            for (Listener l : listeners) l.onPlaying(song, sourceName, quality);
        });
    }

    private void notifyPaused() {
        main.post(() -> {
            for (Listener l : listeners) l.onPaused();
        });
    }
}
