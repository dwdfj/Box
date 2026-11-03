package com.github.tvbox.osc.music;

import android.util.Log;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 小贾影视仓 v27.10: 取链换方案 —— JS 音源运行时反复不稳, 改为直连 HTTP 取链端点链。
 * 端点经 Python 数据面实测(含 flac/lossless 无损 64MB 音频校验):
 *   ep0 洛雪  c.wwwweb.top      POST {source,musicId,quality} -> url (flac/320k/128k 均通)
 *   ep1 笒音  mcp.nianxinxz.com GET  wy.php?id=&level=         -> url (lossless/320k 通)
 * (haitangw 停服、103.79 卡密端点失联, 已剔除)
 * 任一端点拿到 http 开头 URL 即成功; 全部失败才回调错误(之后 MusicPlayer 再回落 JS 运行时)。
 */
public final class MusicDirect {
    private static final String TAG = "MusicDirect";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** 端点展示名, 顺序与 ep 对应。 */
    public static final String[] NAMES = {"洛雪", "笒音"};

    public interface Cb {
        void onResult(String sourceName, String url, String err);
    }

    private static final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    private MusicDirect() {
    }

    /** quality: flac/320k/128k —— ep1 的 flac 映射为 lossless。 */
    public static void fetch(final String songmid, final String quality, final Cb cb) {
        tryEndpoint(0, songmid, quality, cb);
    }

    private static String mapLevel(String q, int ep) {
        if (ep != 1) return q;
        return "flac".equals(q) ? "lossless" : q;
    }

    private static void tryEndpoint(final int ep, final String songmid, final String quality, final Cb cb) {
        if (ep >= NAMES.length) {
            cb.onResult(null, null, "全部端点失败");
            return;
        }
        try {
            Request req;
            if (ep == 0) {
                JSONObject body = new JSONObject();
                body.put("source", "wy");
                body.put("musicId", songmid);
                body.put("quality", quality);
                req = new Request.Builder()
                        .url("https://c.wwwweb.top/music/url")
                        .header("User-Agent", UA)
                        .post(RequestBody.create(MediaType.parse("application/json"), body.toString()))
                        .build();
            } else {
                req = new Request.Builder()
                        .url("https://mcp.nianxinxz.com/share/ceshi/wy.php?id=" + songmid
                                + "&level=" + mapLevel(quality, ep))
                        .header("User-Agent", UA)
                        .get()
                        .build();
            }
            http.newCall(req).enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(Call call, java.io.IOException e) {
                    Log.w(TAG, NAMES[ep] + " fail: " + e.getMessage());
                    tryEndpoint(ep + 1, songmid, quality, cb);
                }

                @Override
                public void onResponse(Call call, Response resp) throws java.io.IOException {
                    String bodyStr = resp.body() != null ? resp.body().string() : "";
                    String url = null;
                    try {
                        JSONObject d = new JSONObject(bodyStr);
                        url = optUrl(d, "url");
                        if (url == null) {
                            JSONObject data = d.optJSONObject("data");
                            if (data != null) url = optUrl(data, "url");
                        }
                    } catch (Throwable ignored) {
                        java.util.regex.Matcher m = java.util.regex.Pattern
                                .compile("https?://[\\w./?=&:%-]+").matcher(bodyStr);
                        if (m.find()) url = m.group();
                    }
                    if (url != null && url.startsWith("http")) {
                        final String fUrl = url;
                        main(() -> cb.onResult(NAMES[ep], fUrl, null));
                    } else {
                        Log.w(TAG, NAMES[ep] + " no-url " + resp.code() + " "
                                + bodyStr.substring(0, Math.min(120, bodyStr.length())));
                        tryEndpoint(ep + 1, songmid, quality, cb);
                    }
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, NAMES[ep] + " err", t);
            tryEndpoint(ep + 1, songmid, quality, cb);
        }
    }

    private static String optUrl(JSONObject d, String field) {
        String u = d.optString(field, null);
        return (u != null && u.startsWith("http")) ? u : null;
    }

    private static void main(Runnable r) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }
}
