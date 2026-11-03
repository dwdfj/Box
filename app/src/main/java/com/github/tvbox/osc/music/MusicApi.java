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
                }, cb);
    }

    // ---------------- 榜单(音乐厅第一组) ----------------

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
                }, cb);
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
