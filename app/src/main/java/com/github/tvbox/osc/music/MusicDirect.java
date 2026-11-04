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
 * 小贾影视仓 v27.14: 取链为「独立线路」模型 —— 照 Voyage 的做法, 每条音源是一条可自由挑选的线路,
 * 用户选了哪条就只用哪条, 不再像 v27.10 那样 ep0 成功就永久跳过 ep1(这导致实际永远是洛雪,
 * 笒音代码存在但从未被调用)。
 *
 * 线路实测(2026-11):
 *   LINE_LUOXUE  洛雪   c.wwwweb.top      POST {source,musicId,quality}  flac/320k/128k 全通
 *   LINE_CENYIN  笒音   mcp.nianxinxz.com GET  wy.php?id=&level=         lossless/320k 通
 *   LINE_VOYAGE  Voyage yy.zddyr.top      GET  /web/api/play.php?id=&quality=&name=&singer=...
 *                ★ v27.16 换接口: 原来调的 /lx/api/ 是站内「内部转发层」, 直连恒返回
 *                  503 queue-full(队列满载, 即便间隔 20 秒也一样), 并不是线路故障。
 *                  官方网页版走的是 /web/api/play.php, 实测 0.5~0.8 秒返回 200:
 *                  {"code":200,"data":{"url":"https://m701.music.126.net/...flac","quality":"flac"}}
 *                  返回的 master/atmos 是明文 FLAC(实测首 4 字节 fLaC), 不需要 ekey 解密。
 *                ★ 老 id 会 500: 网易旧 id(如 186016)在该后端取链失败, 需用 search 拿到的新 id。
 * 任一条线路失败只报该线路的错误, 由用户决定切不切线路。
 */
public final class MusicDirect {
    private static final String TAG = "MusicDirect";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** 线路 id(与 UI 顺序一致)。 */
    public static final int LINE_LUOXUE = 0;
    public static final int LINE_CENYIN = 1;
    public static final int LINE_VOYAGE = 2;

    /** 线路展示名。 */
    public static final String[] NAMES = {"洛雪", "笒音", "Voyage"};

    /** 线路说明(UI 副标题)。 */
    public static final String[] DESCS = {"flac 无损 · 稳定", "lossless 母带 · 更快", "24bit 母带 · 全档位"};

    /** Voyage 官方网页版 API 基址(取链入口, 不是 /lx/api 内部层)。 */
    private static final String VOYAGE_API = "https://yy.zddyr.top/web/api/play.php";
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

    /** 按指定线路取链(不带歌曲元信息, 兼容旧调用)。line 越界时自动收敛到 0。 */
    public static void fetch(int line, String songmid, String quality, Cb cb) {
        fetch(line, songmid, quality, null, null, null, cb);
    }

    /**
     * 按指定线路取链。
     *
     * @param name    歌名   —— Voyage 后端取链需要(name/singer 会参与上游匹配)
     * @param singer  歌手
     * @param albumId 专辑 id —— Voyage 的后端某些平台需要
     */
    public static void fetch(int line, final String songmid, final String quality,
                             final String name, final String singer, final String albumId,
                             final Cb cb) {
        final int ep = (line < 0 || line >= NAMES.length) ? LINE_LUOXUE : line;
        if (ep == LINE_VOYAGE) {
            voyageTry(songmid, quality, name, singer, albumId, 0, cb);
            return;
        }
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
                // 笒音
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
     * Voyage 线路: GET /web/api/play.php?source=wy&id=&quality=&name=&singer=&albumName=...
     * 这是官方网页版(星海音乐)用的真实取链入口, 返回
     * {"code":200,"data":{"url":"https://m701.music.126.net/...","lyric":"","cover":"","quality":"flac"}}。
     *
     * v27.16 之前调的是 /lx/api/, 那是站内「内部转发层」: 直连恒返回
     * 503 {"code":503,"msg":"当前请求量过大"} + X-Limit-Reason: queue-full,
     * 间隔 20 秒也一样, 因为额度被前端 play.php 占掉了 —— 不是线路坏了, 是入口找错了。
     *
     * @param qIdx 音质档位下标: 失败时向下一档降级重试(高解析档位对冷门曲目更容易 404)
     */
    private static void voyageTry(final String songmid, final String quality,
                                  final String name, final String singer, final String albumId,
                                  final int qIdx, final Cb cb) {
        // v27.16: flac 优先 —— master/hires 对不少曲目上游无资源(500), 放前面会白等。
        final String[] qs = {"flac", "320k", "hires", "master", "128k"};
        if (qIdx >= qs.length) {
            main(() -> cb.onResult(NAMES[LINE_VOYAGE], null,
                    NAMES[LINE_VOYAGE] + " 该曲目无可用音质(可切洛雪线路)"));
            return;
        }
        final String q = voyageQuality(qs[qIdx]);
        try {
            String url = VOYAGE_API
                    + "?source=wy"
                    + "&id=" + android.net.Uri.encode(songmid)
                    + "&quality=" + android.net.Uri.encode(q)
                    + "&name=" + android.net.Uri.encode(name == null ? "" : name)
                    + "&singer=" + android.net.Uri.encode(singer == null ? "" : singer)
                    + "&albumName=&interval=&hash="
                    + "&albumId=" + android.net.Uri.encode(albumId == null ? "" : albumId)
                    + "&urlId=" + android.net.Uri.encode(songmid)
                    + "&copyrightId=";
            Request req = new Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    .header("Referer", "https://yy.zddyr.top/web/")
                    .header("Accept", "application/json")
                    .get()
                    .build();
            http.newCall(req).enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(Call call, java.io.IOException e) {
                    Log.w(TAG, "Voyage@" + q + " fail: " + e.getMessage());
                    voyageTry(songmid, quality, name, singer, albumId, qIdx + 1, cb);
                }

                @Override
                public void onResponse(Call call, Response resp) throws java.io.IOException {
                    String bodyStr = resp.body() != null ? resp.body().string() : "";
                    String u = null;
                    try {
                        JSONObject d = new JSONObject(bodyStr);
                        JSONObject data = d.optJSONObject("data");
                        if (data != null) u = optUrl(data, "url");
                        if (u == null) u = optUrl(d, "url");
                    } catch (Throwable ignored) {
                    }
                    if (u != null) {
                        final String fUrl = u;
                        main(() -> cb.onResult(NAMES[LINE_VOYAGE], fUrl, null));
                        return;
                    }
                    Log.w(TAG, "Voyage no-url " + resp.code() + " q=" + q + " "
                            + bodyStr.substring(0, Math.min(120, bodyStr.length())));
                    // 500(老 id/资源缺失) 与 无 url 一律降档重试; 503/429 是限流, 同样降档(别空等)
                    voyageTry(songmid, quality, name, singer, albumId, qIdx + 1, cb);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "Voyage err", t);
            voyageTry(songmid, quality, name, singer, albumId, qIdx + 1, cb);
        }
    }

    /** Voyage 后端音质档位与本地一致(128k/320k/flac/hires/master), 不认识的档位退回 flac。 */
    private static String voyageQuality(String q) {
        if (q == null) return "flac";
        switch (q) {
            case "128k":
            case "320k":
            case "flac":
            case "hires":
            case "master":
            case "atmos":
                return q;
            default:
                return "flac";
        }
    }

    /**
     * 笒音档位映射(2026-11 实测): level=lossless / hires 有地址,
     * 320k / 128k / flac / master 都返回 {"code":200,"url":""} —— 空串等于没取到。
     * 所以只有这两档原样传, 其余一律按 lossless 请求, 避免连报几次失败。
     */
    private static String mapLevel(String q, int ep) {
        if (ep == LINE_CENYIN) {
            return ("hires".equals(q) || "lossless".equals(q)) ? q : "lossless";
        }
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
