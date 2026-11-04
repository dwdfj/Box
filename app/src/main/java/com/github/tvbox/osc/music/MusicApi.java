package com.github.tvbox.osc.music;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 小贾影视仓 v27.3: 内置音乐数据层(网易公开接口, 无需登录) —— 参照喵音乐「音乐厅」结构:
 * 官方榜单 + 精品歌单(带封面) -> 歌单详情(歌曲带封面) -> 洛雪源取链播放。
 * 所有回调回主线程。err 非空即失败。
 */
public class MusicApi {
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/116.0.0.0 Safari/537.36";
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build();

    public static class Song {
        public String songmid;   // wy 平台 id, 供洛雪源取链
        public String name;
        public String singer;
        public String album;
        public String albumId;   // 专辑 id, Voyage 官方接口需要
        public String cover;     // 专辑/歌曲封面
        public long durationMs;
    }

    /** 歌单/榜单条目(封面墙用)。 */
    public static class Sheet {
        public String id;
        public String name;
        public String cover;
        public String sub;   // 更新频率/播放量描述
    }

    public interface Callback<T> {
        void onResult(String error, T data);
    }

    // ---------------- 搜索 ----------------

    public static void search(final String keyword, final int page, final Callback<List<Song>> cb) {
        postForm("https://music.163.com/api/search/get/web",
                "s=" + enc(keyword) + "&type=1&offset=" + ((page - 1) * 30) + "&limit=30&total=true",
                new Parser<List<Song>>() {
                    @Override
                    public List<Song> parse(org.json.JSONObject root) throws Exception {
                        List<Song> out = new ArrayList<>();
                        org.json.JSONObject result = root.optJSONObject("result");
                        org.json.JSONArray songs = result == null ? null : result.optJSONArray("songs");
                        if (songs != null) {
                            for (int i = 0; i < songs.length(); i++) {
                                org.json.JSONObject s = songs.optJSONObject(i);
                                if (s == null) continue;
                                Song song = new Song();
                                song.songmid = String.valueOf(s.optLong("id"));
                                song.name = s.optString("name", "");
                                song.singer = joinArtists(s.optJSONArray("artists"));
                                org.json.JSONObject album = s.optJSONObject("album");
                                song.album = album == null ? "" : album.optString("name", "");
                                song.cover = album == null ? "" : album.optString("picUrl", "");
                                song.durationMs = s.optLong("duration");
                                out.add(song);
                            }
                        }
                        return out;
                    }
                }, new Callback<List<Song>>() {
                    @Override
                    public void onResult(String error, List<Song> data) {
                        if (error != null || data == null) {
                            cb.onResult(error, data);
                            return;
                        }
                        // v27.16: 搜索接口不带 picUrl, 补一次封面再回给 UI
                        fillCovers(data, cb);
                    }
                });
    }

    public static void topLists(final Callback<List<Sheet>> cb) {
        get("https://music.163.com/api/toplist/detail", new Parser<List<Sheet>>() {
            @Override
            public List<Sheet> parse(org.json.JSONObject root) throws Exception {
                List<Sheet> out = new ArrayList<>();
                org.json.JSONArray list = root.optJSONArray("list");
                if (list != null) {
                    for (int i = 0; i < list.length() && i < 12; i++) {
                        org.json.JSONObject s = list.optJSONObject(i);
                        if (s == null) continue;
                        Sheet sheet = new Sheet();
                        sheet.id = String.valueOf(s.optLong("id"));
                        sheet.name = s.optString("name", "");
                        sheet.cover = s.optString("coverImgUrl", s.optString("picUrl", ""));
                        sheet.sub = s.optString("updateFrequency", "");
                        out.add(sheet);
                    }
                }
                return out;
            }
        }, cb);
    }

    // ---------------- 精品歌单(音乐厅第二组) ----------------

    public static void hotSheets(final String cat, final Callback<List<Sheet>> cb) {
        get("https://music.163.com/api/playlist/highquality/list?cat=" + enc(cat) + "&limit=24",
                new Parser<List<Sheet>>() {
                    @Override
                    public List<Sheet> parse(org.json.JSONObject root) throws Exception {
                        List<Sheet> out = new ArrayList<>();
                        org.json.JSONArray list = root.optJSONArray("playlists");
                        if (list != null) {
                            for (int i = 0; i < list.length(); i++) {
                                org.json.JSONObject s = list.optJSONObject(i);
                                if (s == null) continue;
                                Sheet sheet = new Sheet();
                                sheet.id = String.valueOf(s.optLong("id"));
                                sheet.name = s.optString("name", "");
                                sheet.cover = s.optString("coverImgUrl", "");
                                sheet.sub = s.optString("copywriter", "");
                                out.add(sheet);
                            }
                        }
                        return out;
                    }
                }, cb);
    }

    // ---------------- 歌单详情 ----------------

    public static void sheetDetail(final String id, final Callback<List<Song>> cb) {
        get("https://music.163.com/api/playlist/detail?id=" + enc(id) + "&n=60",
                new Parser<List<Song>>() {
                    @Override
                    public List<Song> parse(org.json.JSONObject root) throws Exception {
                        List<Song> out = new ArrayList<>();
                        org.json.JSONObject result = root.optJSONObject("result");
                        org.json.JSONArray tracks = result == null ? null : result.optJSONArray("tracks");
                        if (tracks != null) {
                            for (int i = 0; i < tracks.length() && i < 60; i++) {
                                org.json.JSONObject s = tracks.optJSONObject(i);
                                if (s == null) continue;
                                Song song = new Song();
                                song.songmid = String.valueOf(s.optLong("id"));
                                song.name = s.optString("name", "");
                                song.singer = joinArtists(s.optJSONArray("artists"));
                                org.json.JSONObject album = s.optJSONObject("album");
                                song.album = album == null ? "" : album.optString("name", "");
                                song.cover = album == null ? "" : album.optString("picUrl", "");
                                song.durationMs = s.optLong("duration");
                                out.add(song);
                            }
                        }
                        return out;
                    }
                }, new Callback<List<Song>>() {
                    @Override
                    public void onResult(String error, List<Song> data) {
                        if (error != null || data == null) {
                            cb.onResult(error, data);
                            return;
                        }
                        // v27.16: 歌单详情同样缺 picUrl, 补一次封面
                        fillCovers(data, cb);
                    }
                });
    }

    /**
     * 小贾影视仓 v27.16: 批量补封面。
     *
     * 根因: 搜索/歌单用的 /api/search/get/web 与 /api/playlist/detail 只返回 album.picId,
     * **不返回 picUrl**(实测周杰伦搜出 30 首, picUrl 有值的 0 首), 于是 song.cover 全是空字符串,
     * 界面上就是一片没有封面的黑块。
     *
     * 而 /api/song/detail 会完整返回 album.picUrl(https://p2.music.126.net/...jpg)。
     * 这里按 100 个 id 一批补一次, 只补空 cover 的歌, 全都有封面时不发请求。
     */
    public static void fillCovers(final List<Song> songs, final Callback<List<Song>> cb) {
        if (songs == null) {
            cb.onResult(null, songs);
            return;
        }
        final StringBuilder ids = new StringBuilder("[");
        int need = 0;
        for (Song s : songs) {
            if (s == null || s.songmid == null) continue;
            if (s.cover != null && !s.cover.isEmpty()) continue;
            if (need > 0) ids.append(",");
            ids.append("\"").append(s.songmid).append("\"");
            need++;
        }
        if (need == 0) {
            cb.onResult(null, songs);
            return;
        }
        ids.append("]");
        // v27.24: 先走 v3 接口 —— 老的 /api/song/detail 在部分网络下会返回空 songs 或 403,
        // 那时整页封面就只剩首字渐变图。v3 的 al.picUrl 稳定, 批量一次补齐。
        get("https://music.163.com/api/v3/song/detail?c=" + enc(ids.toString()),
                new Parser<List<Song>>() {
                    @Override
                    public List<Song> parse(org.json.JSONObject root) throws Exception {
                        org.json.JSONArray arr = root.optJSONArray("songs");
                        if (arr == null) return songs;
                        Map<String, org.json.JSONObject> byId = new HashMap<>();
                        for (int i = 0; i < arr.length(); i++) {
                            org.json.JSONObject s = arr.optJSONObject(i);
                            if (s != null) byId.put(String.valueOf(s.optLong("id")), s);
                        }
                        for (Song song : songs) {
                            if (song == null) continue;
                            org.json.JSONObject d = byId.get(song.songmid);
                            if (d == null) continue;
                            org.json.JSONObject al = d.optJSONObject("al");
                            if (al == null) continue;
                            if (song.cover == null || song.cover.isEmpty()) {
                                song.cover = al.optString("picUrl", "");
                            }
                            if (song.albumId == null || song.albumId.isEmpty()) {
                                long aid = al.optLong("id", 0);
                                song.albumId = aid == 0 ? "" : String.valueOf(aid);
                            }
                            if (song.album == null || song.album.isEmpty()) {
                                song.album = al.optString("name", "");
                            }
                        }
                        return songs;
                    }
                }, new Callback<List<Song>>() {
                    @Override
                    public void onResult(String error, List<Song> data) {
                        if (hasMissingCover(songs)) fillCoversLegacy(songs, cb);
                        else cb.onResult(null, songs);
                    }
                });
    }

    private static boolean hasMissingCover(List<Song> songs) {
        for (Song s : songs) {
            if (s != null && (s.cover == null || s.cover.isEmpty())) return true;
        }
        return false;
    }

    /** 老接口兜底(v3 也拿不到时再试一次; 两路都失败就交给首字占位图)。 */
    private static void fillCoversLegacy(final List<Song> songs, final Callback<List<Song>> cb) {
        final StringBuilder ids = new StringBuilder("[");
        int need = 0;
        for (Song s : songs) {
            if (s == null || s.songmid == null) continue;
            if (s.cover != null && !s.cover.isEmpty()) continue;
            if (need > 0) ids.append(",");
            ids.append("\"").append(s.songmid).append("\"");
            need++;
        }
        if (need == 0) {
            cb.onResult(null, songs);
            return;
        }
        ids.append("]");
        postForm("https://music.163.com/api/song/detail",
                "ids=" + enc(ids.toString()) + "&csrf_token=",
                new Parser<List<Song>>() {
                    @Override
                    public List<Song> parse(org.json.JSONObject root) throws Exception {
                        Map<String, org.json.JSONObject> byId = new HashMap<>();
                        org.json.JSONArray arr = root.optJSONArray("songs");
                        if (arr != null) {
                            for (int i = 0; i < arr.length(); i++) {
                                org.json.JSONObject s = arr.optJSONObject(i);
                                if (s == null) continue;
                                byId.put(String.valueOf(s.optLong("id")), s);
                            }
                        }
                        for (Song song : songs) {
                            if (song == null) continue;
                            org.json.JSONObject d = byId.get(song.songmid);
                            if (d == null) continue;
                            org.json.JSONObject album = d.optJSONObject("album");
                            if (album == null) continue;
                            if (song.cover == null || song.cover.isEmpty()) {
                                song.cover = album.optString("picUrl", "");
                            }
                            if (song.albumId == null || song.albumId.isEmpty()) {
                                long aid = album.optLong("id", 0);
                                song.albumId = aid == 0 ? "" : String.valueOf(aid);
                            }
                            if (song.album == null || song.album.isEmpty()) {
                                song.album = album.optString("name", "");
                            }
                        }
                        return songs;
                    }
                }, new Callback<List<Song>>() {
                    @Override
                    public void onResult(String error, List<Song> data) {
                        // 补封面失败不该让整页失败: 原样回传, 由 UI 的占位图兜底
                        cb.onResult(null, songs);
                    }
                });
    }

    // ---------------- 通用执行 ----------------

    private interface Parser<T> {
        T parse(org.json.JSONObject root) throws Exception;
    }

    private static void get(final String url, final Parser<?> parser, final Callback<?> cb) {
        run(parser, cb, () -> new Request.Builder().url(url)
                .header("User-Agent", UA).header("Referer", "https://music.163.com").get().build());
    }

    private static void postForm(final String url, final String form, final Parser<?> parser, final Callback<?> cb) {
        run(parser, cb, () -> new Request.Builder().url(url)
                .header("User-Agent", UA).header("Referer", "https://music.163.com")
                .post(okhttp3.RequestBody.create(
                        okhttp3.MediaType.parse("application/x-www-form-urlencoded"), form))
                .build());
    }

    private static void run(final Parser<?> parser, final Callback<?> cb, final ReqMaker maker) {
        new Thread(() -> {
            String err = null;
            Object data = null;
            try {
                Response resp = HTTP.newCall(maker.make()).execute();
                String text = resp.body() != null ? resp.body().string() : "";
                resp.close();
                org.json.JSONObject root = new org.json.JSONObject(text);
                data = parser.parse(root);
            } catch (Throwable t) {
                err = String.valueOf(t.getMessage());
            }
            final String fErr = err;
            final Object fData = data;
            main(() -> ((Callback<Object>) cb).onResult(fErr, fData));
        }).start();
    }

    private interface ReqMaker {
        Request make() throws Exception;
    }

    private static void main(Runnable r) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String joinArtists(org.json.JSONArray arts) {
        if (arts == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int j = 0; j < arts.length(); j++) {
            if (j > 0) sb.append("/");
            org.json.JSONObject a = arts.optJSONObject(j);
            sb.append(a == null ? "" : a.optString("name", ""));
        }
        return sb.toString();
    }

    /** 洛雪源取链所需 musicInfo(wy 平台)。 */
    public static Map<String, Object> musicInfoFor(Song song) {
        Map<String, Object> m = new HashMap<>();
        m.put("songmid", song.songmid);
        m.put("hash", "");
        m.put("songId", song.songmid);
        m.put("copyrightId", "");
        m.put("name", song.name);
        m.put("singer", song.singer);
        return m;
    }
}
