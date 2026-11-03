package com.github.tvbox.osc.music;

import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 小贾影视仓 v27.13: 歌词获取 —— 网易云公开歌词接口(无需登录), 返回标准 LRC。
 * 实测 https://music.163.com/api/song/lyric?id=347230&lv=1&kv=1&tv=-1 可用。
 * 优先取翻译歌词(tlyric)无翻译时用原文; 解析后回主线程。
 */
public final class LyricApi {
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    public interface Cb {
        void onLyric(List<LrcParser.Line> lines, String raw, String err);
    }

    private LyricApi() {
    }

    public static void fetch(final String songmid, final Cb cb) {
        new Thread(() -> {
            List<LrcParser.Line> lines = java.util.Collections.emptyList();
            String raw = null;
            String err = null;
            try {
                Request req = new Request.Builder()
                        .url("https://music.163.com/api/song/lyric?id=" + songmid
                                + "&lv=1&kv=1&tv=-1")
                        .header("User-Agent", UA)
                        .header("Referer", "https://music.163.com")
                        .get().build();
                Response resp = HTTP.newCall(req).execute();
                String text = resp.body() != null ? resp.body().string() : "";
                resp.close();
                org.json.JSONObject root = new org.json.JSONObject(text);
                org.json.JSONObject lrc = root.optJSONObject("lrc");
                raw = lrc == null ? null : lrc.optString("lyric", null);
                if (raw == null || raw.trim().isEmpty()) {
                    err = "无歌词";
                } else {
                    lines = LrcParser.parse(raw);
                    if (lines.isEmpty()) err = "歌词为空";
                }
            } catch (Throwable t) {
                err = String.valueOf(t.getMessage());
            }
            final List<LrcParser.Line> fLines = lines;
            final String fRaw = raw;
            final String fErr = err;
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(() -> cb.onLyric(fLines, fRaw, fErr));
        }).start();
    }
}
