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
 * 小贾影视仓 v27.13: 取链改为「独立线路」模型 —— 照 Voyage 的做法, 每条音源是一条可自由挑选的线路,
 * 用户选了哪条就只用哪条, 不再像 v27.10 那样 ep0 成功就永久跳过 ep1(这导致实际永远是洛雪,
 * 笒音代码存在但从未被调用)。
 *
 * 线路实测(2026-11):
 *   LINE_LUOXUE  洛雪  c.wwwweb.top      POST {source,musicId,quality}  flac/320k/128k 全通
 *   LINE_CENYIN  笒音  mcp.nianxinxz.com GET  wy.php?id=&level=         lossless/320k 通
 * 任一条线路失败只报该线路的错误, 由用户决定切不切线路。
 */
public final class MusicDirect {
    private static final String TAG = "MusicDirect";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** 线路 id(与 UI 顺序一致)。 */
    public static final int LINE_LUOXUE = 0;
    public static final int LINE_CENYIN = 1;

    /** 线路展示名。 */
    public static final String[] NAMES = {"洛雪", "笒音"};

    /** 线路说明(UI 副标题)。 */
    public static final String[] DESCS = {"flac 无损 · 稳定", "lossless 母带 · 更快"};

    public interface Cb {
        /** url 非空即成功; 否则 err 说明原因。 */
        void onResult(String sourceName, String url, String err);
    }

    private static final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    private MusicDirect() {
    }

    /** 按指定线路取链。line 越界时自动收敛到 0。 */
    public static void fetch(int line, final String songmid, final String quality, final Cb cb) {
        final int ep = (line < 0 || line >= NAMES.length) ? LINE_LUOXUE : line;
        try {
            Request req;
            if (ep == LINE_LUOXUE) {
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
                    final String msg = e.getMessage() == null ? "网络失败" : e.getMessage();
                    main(() -> cb.onResult(NAMES[ep], null, NAMES[ep] + " 不可达: " + msg));
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
                        main(() -> cb.onResult(NAMES[ep], null, NAMES[ep] + " 无可用地址(" + resp.code() + ")"));
                    }
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, NAMES[ep] + " err", t);
            main(() -> cb.onResult(NAMES[ep], null, NAMES[ep] + " 请求异常"));
        }
    }

    /**
     * 笒音只有 lossless 一档可用 —— 实测 level=320k/128k 都返回 {"code":200,"msg":"解析成功"}
     * 但 url 为 null(空响应), 所以笒音线路一律按 lossless 请求, 否则会连报三次失败。
     * 洛雪按用户选的音质原样请求(flac/320k/128k 三档都通)。
     */
    private static String mapLevel(String q, int ep) {
        if (ep == LINE_CENYIN) return "lossless";
        return q;
    }

    private static String optUrl(JSONObject d, String field) {
        String u = d.optString(field, null);
        return (u != null && u.startsWith("http")) ? u : null;
    }

    private static void main(Runnable r) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }
}
