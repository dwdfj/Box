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
 * 小贾影视仓 v27.9: 取链换方案 —— JS 音源运行时反复不稳, 改为直连 HTTP 取链端点链。
 * 4 个端点均经过 Python 数据面实测(返回真实 mp3 直链, Range 206 audio/mpeg),
 * 逐个尝试, 任一拿到 http 开头 URL 即成功; 全部失败才回调错误(之后 MusicPlayer 再回落 JS 运行时)。
 */
public final class MusicDirect {
    private static final String TAG = "MusicDirect";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    public interface Cb {
        void onResult(String url, String err);
    }

    private static final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    private MusicDirect() {
    }

    /** quality: flac/320k/128k —— 各端点参数自动映射。 */
    public static void fetch(final String songmid, final String quality, final Cb cb) {
        tryEndpoint(0, songmid, quality, cb);
    }

    /** flac 在部分端点叫 lossless。 */
    private static String mapLevel(String q, int ep) {
        if (!"flac".equals(q)) return q;
        return (ep == 1 || ep == 2) ? "lossless" : q;
    }

    private static void tryEndpoint(final int ep, final String songmid, final String quality, final Cb cb) {
        if (ep >= 4) {
            cb.onResult(null, "4个直连端点均失败");
            return;
        }
        try {
            Request req;
            if (ep == 0) {
                // c.wwwweb.top (helloworld.js 同款): POST {source,musicId,quality} -> url
                JSONObject body = new JSONObject();
                body.put("source", "wy");
                body.put("musicId", songmid);
                body.put("quality", quality);
                req = new Request.Builder()
                        .url("https://c.wwwweb.top/music/url")
                        .header("User-Agent", UA)
                        .post(RequestBody.create(MediaType.parse("application/json"), body.toString()))
                        .build();
            } else if (ep == 1) {
                // haitangw: POST {source,rid,level} -> data.url
                JSONObject body = new JSONObject();
                body.put("source", "wy");
                body.put("rid", songmid);
                body.put("level", mapLevel(quality, ep));
                req = new Request.Builder()
                        .url("https://musicserver.haitangw.cc/v1/music/resolve-url")
                        .header("User-Agent", UA)
                        .post(RequestBody.create(MediaType.parse("application/json"), body.toString()))
                        .build();
            } else if (ep == 2) {
                // mcp.nianxinxz: GET wy.php?id=&level= -> url
                req = new Request.Builder()
                        .url("https://mcp.nianxinxz.com/share/ceshi/wy.php?id=" + songmid
                                + "&level=" + mapLevel(quality, ep))
                        .header("User-Agent", UA)
                        .get()
                        .build();
            } else {
                // 103.79.184.97 卡密端点: GET -> url
                req = new Request.Builder()
                        .url("http://103.79.184.97/api/music/url?platform=wy&songId=" + songmid
                                + "&quality=" + quality + "&key=6C1F-53W0-GRKI-EVFG")
                        .header("User-Agent", "lx-music-mobile/2.0.0")
                        .header("X-Card-Key", "6C1F-53W0-GRKI-EVFG")
                        .get()
                        .build();
            }
            http.newCall(req).enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(Call call, java.io.IOException e) {
                    Log.w(TAG, "ep" + ep + " fail: " + e.getMessage());
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
                        // 非 JSON: 整段文本里找 http 链接
                        java.util.regex.Matcher m = java.util.regex.Pattern
                                .compile("https?://[\\w./?=&:%-]+").matcher(bodyStr);
                        if (m.find()) url = m.group();
                    }
                    if (url != null && url.startsWith("http")) {
                        final String fUrl = url;
                        main(() -> cb.onResult(fUrl, null));
                    } else {
                        Log.w(TAG, "ep" + ep + " no-url " + resp.code() + " " + bodyStr.substring(0, Math.min(120, bodyStr.length())));
                        tryEndpoint(ep + 1, songmid, quality, cb);
                    }
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "ep" + ep + " err", t);
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
