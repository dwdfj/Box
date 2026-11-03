package com.github.tvbox.osc.music;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import com.whl.quickjs.wrapper.JSCallFunction;
import com.whl.quickjs.wrapper.JSObject;
import com.whl.quickjs.wrapper.QuickJSContext;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

/**
 * 小贾影视仓 v27.2: 盒内洛雪(LX Music)自定义源运行时 —— 对齐 Voyage 的 QuickJS + preload.js 形态。
 * <p>
 * 每个音源脚本一个 QuickJSContext, 全部跑在同一个专用线程(preload.js 的 __lx_native__ 是同步回调,
 * QuickJS 实例不可跨线程)。Java 侧实现 preload.js 需要的宿主动作:
 * 同步: aes/rsa/random/md5/inflate/deflate; 异步: request(网络, 经 __lx_dispatch__ 回灌)/
 * timer(Handler 调度)/log/init/updateAlert; 取链结果经 JS->host 的 'response' 动作回传。
 * <p>
 * preload.js 与音源脚本来自 assets/lx_music/, 与洛雪桌面版/手机版接口一致。
 */
public class LxRuntime {
    private static final String TAG = "LxRuntime";
    /** 取链整体超时(ms): JS 侧 Promise 永不 settle 时兜底回调, 否则 MusicPlayer.loading 永久卡死。 */
    private static final long URL_TIMEOUT_MS = 15000;
    /** preload.js 的 assets 路径 —— 必须先 evaluate 它, __lx_setup__/__lx_dispatch__ 才存在(v27.4 致命bug)。 */
    private static final String PRELOAD_ASSET = "lx_music/preload.js";
    public static final String EVT_INIT = "init";
    public static final String EVT_UPDATE_ALERT = "updateAlert";
    public static final String EVT_LOG = "log";

    public interface EventListener {
        void onEvent(String sourceKey, String event, String json);
    }

    /** 一次取链请求的完成回调(data=成功 JSON: {url,ekey,format} 或 {message})。 */
    public interface MusicUrlCallback {
        void onResult(String sourceKey, String error, String url, String ekey, String format);
    }

    private final HandlerThread jsThread;
    private final Handler jsHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final OkHttpClient http;
    private final EventListener listener;
    private final Map<String, String> sources = new LinkedHashMap<>(); // key -> name
    private final Map<String, JsEnv> envs = new LinkedHashMap<>();     // key -> js env
    private final Map<Integer, Call> calls = new ConcurrentHashMap<>();
    private final Map<Integer, MusicUrlCallback> pendingUrls = new ConcurrentHashMap<>();
    private final Map<Integer, String> pendingUrlKeys = new ConcurrentHashMap<>();
    private final Map<Integer, Runnable> pendingTimeouts = new ConcurrentHashMap<>();
    private String preloadScript; // v27.5: 每个ctx先 evaluate preload 再装配音源
    private int seq = 0;

    private static class JsEnv {
        QuickJSContext ctx;
        String key;
        String rawScript;
        String name;
        String dispatch; // __lx_dispatch__ 表达式模板
    }

    public LxRuntime(EventListener listener) {
        this.listener = listener;
        jsThread = new HandlerThread("LxJs");
        jsThread.start();
        jsHandler = new Handler(jsThread.getLooper());
        http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
    }

    public Map<String, String> getSources() {
        return sources;
    }

    /** 在 js 线程加载一个音源脚本(assets lx_music/sources/<file>)。 */
    public void loadSource(final String key, final String name, final String rawScript) {
        jsHandler.post(() -> {
            try {
                if (preloadScript == null) {
                    preloadScript = readAsset(PRELOAD_ASSET);
                }
                JsEnv env = new JsEnv();
                env.key = key;
                env.name = name;
                env.rawScript = rawScript;
                QuickJSContext ctx = QuickJSContext.create();
                env.ctx = ctx;
                JSObject global = ctx.getGlobalObject();
                // 必须用 set() —— setProperty() 不注册 JSCallFunction(v27.2 全断的根因):
                // set() 会 putCallFunction 把 Java 回调登记进 callFunctionMap, native 层才能回调。
                ctx.set(global, "__lx_native__", (JSCallFunction) args -> onNative(env, args));
                // v27.5 致命bug修复: v27.2~v27.4 从未 evaluate 过 preload.js, globalThis.__lx_setup__
                // 根本不存在 → TypeError 被吞 → 所有源 "source not loaded"。必须先装配 preload。
                ctx.evaluate(preloadScript);
                String setupJson = "{\"version\":\"2.0.0\",\"env\":\"mobile\",\"info\":{\"name\":"
                        + jq(name) + ",\"description\":\"box builtin\",\"version\":\"1.0\",\"author\":\"xj\"}}";
                String escaped = jq(rawScript);
                // 与 Voyage 相同的装配顺序: __lx_setup__(key, setupJson, rawScript)
                // 注意: __lx_setup__ 只把 rawScript 存进 currentScriptInfo(preload.js:572), 从不执行它 ——
                // 音源脚本必须由宿主在 setup 之后、同一 ctx 里直接 evaluate(v27.5 第二个致命bug:
                // 此前从未跑过音源脚本, handlers.request 永远为空 → "Request event is not defined")。
                ctx.evaluate("globalThis.__lx_setup__(" + jq(key) + "," + setupJson + "," + escaped + ");");
                ctx.evaluate(rawScript);
                envs.put(key, env);
                sources.put(key, name);
                Log.d(TAG, "source loaded: " + key);
            } catch (Throwable t) {
                Log.e(TAG, "loadSource fail " + key, t);
                fire(key, EVT_INIT, "{\"status\":false,\"message\":\"" + jq(t.getMessage()) + "\"}");
            }
        });
    }

    /**
     * 请求取链: musicInfo 需含 songmid/hash 等平台字段, quality 如 128k/320k/flac。
     * v27.5 协议修正: 洛雪协议里 source 是"平台码"(wy/kg/tx/kw/mg)而非音源文件名,
     * 音质字段是 info.type 而非 info.quality —— 此前两个字段全错, 即使 preload 装上也取不到链。
     */
    public void getMusicUrl(final String sourceKey, final String platform, final Map<String, Object> musicInfo,
                            final String quality, final MusicUrlCallback cb) {
        jsHandler.post(() -> {
            try {
                JsEnv env = envs.get(sourceKey);
                if (env == null || env.ctx == null) {
                    cb.onResult(sourceKey, "source not loaded", null, null, null);
                    return;
                }
                int id = ++seq;
                pendingUrls.put(id, cb);
                pendingUrlKeys.put(id, sourceKey);
                // 超时兜底: 回调只触发一次(先到先得), 防止 JS 侧悬挂后 loading 永久卡死
                Runnable timeout = () -> {
                    MusicUrlCallback c = pendingUrls.remove(id);
                    pendingUrlKeys.remove(id);
                    if (c != null) c.onResult(sourceKey, "取链超时", null, null, null);
                };
                pendingTimeouts.put(id, timeout);
                mainHandler.postDelayed(timeout, URL_TIMEOUT_MS);
                String info = "{\"musicInfo\":" + mapToJson(musicInfo) + ",\"type\":" + jq(quality) + "}";
                String payload = "{\"id\":" + id + ",\"source\":" + jq(platform) + ",\"action\":\"musicUrl\",\"info\":" + info + "}";
                evalDispatch(env, "request", payload);
            } catch (Throwable t) {
                cb.onResult(sourceKey, String.valueOf(t.getMessage()), null, null, null);
            }
        });
    }

    public void destroy() {
        jsHandler.post(() -> {
            for (JsEnv env : envs.values()) {
                try {
                    env.ctx.destroy();
                } catch (Throwable ignored) {
                }
            }
            envs.clear();
            sources.clear();
            for (Call c : calls.values()) {
                try {
                    c.cancel();
                } catch (Throwable ignored) {
                }
            }
            calls.clear();
            pendingUrls.clear();
            pendingUrlKeys.clear();
            for (Runnable r : pendingTimeouts.values()) mainHandler.removeCallbacks(r);
            pendingTimeouts.clear();
        });
        jsThread.quitSafely();
    }

    private static String readAsset(String path) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        java.io.InputStream is = AppContextHolder.get().getAssets().open(path);
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toString("UTF-8");
    }

    // ---------------- JS -> host (同步) ----------------

    private String onNative(JsEnv env, Object... args) {
        try {
            String key = String.valueOf(arg(args, 0));
            String action = String.valueOf(arg(args, 1));
            String data = String.valueOf(arg(args, 2));
            if (!key.equals(env.key)) return "null";
            switch (action) {
                case "aes":
                    return doAes(data);
                case "md5":
                    return doMd5(data);
                case "random":
                    return doRandom(data);
                case "inflate":
                    return "{\"value\":\"" + b64(zlib(data, true)) + "\"}";
                case "deflate":
                    return "{\"value\":\"" + b64(zlib(data, false)) + "\"}";
                case "rsa":
                    return doRsa(data);
                case "request":
                    doRequest(env, data);
                    return "null";
                case "cancelRequest": {
                    Map<String, Object> m = json(data);
                    Object id = m.get("id");
                    if (id instanceof Number) {
                        Call c = calls.remove(((Number) id).intValue());
                        if (c != null) c.cancel();
                    }
                    return "null";
                }
                case "timer": {
                    Map<String, Object> m = json(data);
                    final int id = ((Number) m.get("id")).intValue();
                    final boolean repeat = m.get("repeat") != null && Boolean.parseBoolean(String.valueOf(m.get("repeat")));
                    long delay = m.get("delay") instanceof Number ? ((Number) m.get("delay")).longValue() : 0;
                    if (repeat) {
                        // preload 里 repeat 的重挂是 fireTimer 时 call('timer'), 这里只管单次触发
                        mainHandler.postDelayed(() -> dispatchTimer(env, id, true), delay);
                    } else {
                        mainHandler.postDelayed(() -> dispatchTimer(env, id, false), delay);
                    }
                    return "null";
                }
                case "log": {
                    Map<String, Object> m = json(data);
                    Log.d(TAG, env.key + ": " + m.get("text"));
                    return "null";
                }
                case "init": {
                    Map<String, Object> m = json(data);
                    boolean ok = Boolean.TRUE.equals(m.get("status"));
                    Log.i(TAG, "init " + env.key + " ok=" + ok);
                    fire(env.key, EVT_INIT, data);
                    return "null";
                }
                case "updateAlert": {
                    fire(env.key, EVT_UPDATE_ALERT, data);
                    return "null";
                }
                case "response": {
                    // JS -> host: 取链结果(handleRequest 的 reply)
                    Map<String, Object> m = json(data);
                    int id = m.get("id") instanceof Number ? ((Number) m.get("id")).intValue() : -1;
                    MusicUrlCallback cb = pendingUrls.remove(id);
                    pendingUrlKeys.remove(id);
                    Runnable to = pendingTimeouts.remove(id);
                    if (to != null) mainHandler.removeCallbacks(to);
                    if (cb == null) return "null";
                    boolean ok = Boolean.TRUE.equals(m.get("ok"));
                    if (ok) {
                        // v27.5 修复: data 可能是纯字符串 URL(preload handleRequest 对字符串 url
                        // 回 reply(true, result), data 不是对象) —— 此前强转 Map 抛 ClassCastException,
                        // 且 cb 已被 remove, 回调永久丢失。
                        Object dRaw = m.get("data");
                        String url;
                        String ekey = null;
                        String format = null;
                        if (dRaw instanceof Map) {
                            Map<String, Object> d = (Map<String, Object>) dRaw;
                            url = str(d.get("url"));
                            ekey = str(d.get("ekey"));
                            format = str(d.get("format"));
                        } else {
                            url = str(dRaw);
                        }
                        final String fUrl = url, fEkey = ekey, fFmt = format;
                        mainHandler.post(() -> cb.onResult(env.key, fUrl == null ? "empty url" : null, fUrl, fEkey, fFmt));
                    } else {
                        String msg = str(m.get("message"));
                        mainHandler.post(() -> cb.onResult(env.key, msg, null, null, null));
                    }
                    return "null";
                }
                default:
                    return "null";
            }
        } catch (Throwable t) {
            Log.e(TAG, "onNative " + env.key, t);
            return "{\"error\":\"" + jq(String.valueOf(t)) + "\"}";
        }
    }

    private void dispatchTimer(JsEnv env, int id, boolean repeat) {
        try {
            evalDispatch(env, "timer", "{\"id\":" + id + ",\"repeat\":" + repeat + "}");
        } catch (Throwable t) {
            Log.e(TAG, "timer", t);
        }
    }

    // ---------------- 网络 ----------------

    private void doRequest(JsEnv env, String dataJson) {
        Map<String, Object> m = json(dataJson);
        final int id = m.get("id") instanceof Number ? ((Number) m.get("id")).intValue() : 0;
        String url = str(m.get("url"));
        String method = str(m.get("method"));
        if (method == null || method.isEmpty()) method = "GET";
        boolean binary = Boolean.parseBoolean(str(m.get("binary")));
        try {
            Request.Builder rb = new Request.Builder().url(url);
            Map<String, Object> headers = (Map<String, Object>) m.get("headers");
            if (headers != null) {
                for (Map.Entry<String, Object> e : headers.entrySet()) {
                    if (e.getValue() != null) rb.header(e.getKey(), String.valueOf(e.getValue()));
                }
            }
            Map<String, Object> body = (Map<String, Object>) m.get("body");
            RequestBody reqBody = null;
            if (body != null) {
                String kind = str(body.get("kind"));
                String value = str(body.get("value"));
                Map<String, Object> fields = (Map<String, Object>) body.get("fields");
                if ("bytes".equals(kind)) {
                    reqBody = RequestBody.create(MediaType.parse("application/octet-stream"), unB64(value));
                } else if ("form".equals(kind) || ("json".equals(kind) && fields != null && !fields.isEmpty()
                        && hasHeader(headers, "application/x-www-form-urlencoded"))) {
                    reqBody = formBody(fields);
                } else if ("form-text".equals(kind)) {
                    reqBody = RequestBody.create(MediaType.parse("application/x-www-form-urlencoded"), value);
                } else if ("multipart".equals(kind)) {
                    MultipartBody.Builder mb = new MultipartBody.Builder().setType(MultipartBody.FORM);
                    if (fields != null) {
                        for (Map.Entry<String, Object> e : fields.entrySet()) {
                            mb.addFormDataPart(e.getKey(), String.valueOf(e.getValue()));
                        }
                    }
                    reqBody = mb.build();
                } else if ("json".equals(kind)) {
                    reqBody = RequestBody.create(MediaType.parse("application/json"), value);
                } else if (value != null) {
                    reqBody = RequestBody.create(MediaType.parse("text/plain"), value);
                }
            }
            switch (method.toUpperCase()) {
                case "POST":
                    rb.post(reqBody != null ? reqBody
                            : RequestBody.create(MediaType.parse("text/plain"), ""));
                    break;
                case "PUT":
                    rb.put(reqBody != null ? reqBody : RequestBody.create(MediaType.parse("text/plain"), ""));
                    break;
                case "HEAD":
                    rb.head();
                    break;
                case "DELETE":
                    if (reqBody != null) rb.delete(reqBody);
                    else rb.delete();
                    break;
                default:
                    if (reqBody != null) rb.post(reqBody);
                    else rb.get();
            }
            Call call = http.newCall(rb.build());
            calls.put(id, call);
            final long started = System.currentTimeMillis();
            call.enqueue(new Callback() {
                @Override
                public void onFailure(Call c, IOException e) {
                    calls.remove(id);
                    if (call.isCanceled()) return;
                    replyNet(env, id, null, 0, null, null, String.valueOf(e.getMessage()), binary);
                }

                @Override
                public void onResponse(Call c, Response resp) throws IOException {
                    calls.remove(id);
                    byte[] bytes;
                    try {
                        bytes = resp.body() != null ? resp.body().bytes() : new byte[0];
                    } catch (IOException e) {
                        replyNet(env, id, null, 0, null, null, String.valueOf(e.getMessage()), binary);
                        return;
                    }
                    Map<String, String> hs = new LinkedHashMap<>();
                    for (Map.Entry<String, List<String>> e : resp.headers().toMultimap().entrySet()) {
                        hs.put(e.getKey(), join(e.getValue(), ", "));
                    }
                    replyNet(env, id, resp.request().url().toString(), resp.code(),
                            resp.message(), hs, null, binary, bytes);
                }
            });
        } catch (Throwable t) {
            replyNet(env, id, null, 0, null, null, String.valueOf(t.getMessage()), binary);
        }
    }

    private void replyNet(JsEnv env, int id, String url, int status, String msg,
                          Map<String, String> headers, String error, boolean binary, byte[]... bodyBytes) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"id\":").append(id);
        if (error != null) {
            sb.append(",\"error\":").append(jq(error));
        } else {
            sb.append(",\"status\":").append(status);
            sb.append(",\"message\":").append(jq(msg == null ? "" : msg));
            sb.append(",\"url\":").append(jq(url));
            sb.append(",\"binary\":").append(binary);
            sb.append(",\"headers\":{");
            if (headers != null) {
                boolean first = true;
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append(jq(e.getKey())).append(':').append(jq(e.getValue()));
                }
            }
            sb.append("},\"body\":\"").append(bodyBytes != null && bodyBytes.length > 0 ? b64(bodyBytes[0]) : "").append("\"");
        }
        sb.append("}");
        final String payload = sb.toString();
        try {
            evalDispatch(env, "response", payload);
        } catch (Throwable t) {
            Log.e(TAG, "replyNet", t);
        }
    }

    private void replyNet(JsEnv env, int id, String url, int status, String msg,
                          Map<String, String> headers, String error, boolean binary) {
        replyNet(env, id, url, status, msg, headers, error, binary, new byte[0]);
    }

    // ---------------- crypto / zlib ----------------

    private String doAes(String data) {
        try {
            Map<String, Object> m = json(data);
            byte[] in = unB64(str(m.get("data")));
            byte[] key = unB64(str(m.get("key")));
            byte[] iv = unB64(str(m.get("iv")));
            String mode = str(m.get("mode"));
            String transformation;
            boolean encrypt = !mode.contains("dec");
            if (mode.startsWith("aes-128")) transformation = "AES/CBC/PKCS5Padding";
            else if (mode.startsWith("aes-256")) transformation = "AES/CBC/PKCS5Padding";
            else transformation = "AES/CBC/PKCS5Padding";
            if (mode.contains("ecb")) transformation = "AES/ECB/PKCS5Padding";
            if (mode.contains("nofill") || mode.contains("no-padding") || mode.contains("zeropadding"))
                transformation = mode.contains("ecb") ? "AES/ECB/NoPadding" : "AES/CBC/NoPadding";
            Cipher cipher = Cipher.getInstance(transformation);
            SecretKeySpec ks = new SecretKeySpec(key, "AES");
            if (iv != null && iv.length > 0 && !mode.contains("ecb")) {
                cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, ks, new IvParameterSpec(iv));
            } else {
                cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, ks);
            }
            byte[] out = cipher.doFinal(in);
            return "{\"value\":\"" + b64(out) + "\"}";
        } catch (Throwable t) {
            return "{\"error\":\"" + jq(String.valueOf(t.getMessage())) + "\"}";
        }
    }

    private String doMd5(String data) {
        try {
            Map<String, Object> m = json(data);
            byte[] in = unB64(str(m.get("data")));
            byte[] d = MessageDigest.getInstance("MD5").digest(in);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return "{\"value\":\"" + sb + "\"}";
        } catch (Throwable t) {
            return "{\"error\":\"" + jq(String.valueOf(t.getMessage())) + "\"}";
        }
    }

    private String doRandom(String data) {
        Map<String, Object> m = json(data);
        int size = m.get("size") instanceof Number ? ((Number) m.get("size")).intValue() : 0;
        byte[] out = new byte[Math.max(0, Math.min(size, 65536))];
        new SecureRandom().nextBytes(out);
        return "{\"value\":\"" + b64(out) + "\"}";
    }

    private String doRsa(String data) {
        try {
            Map<String, Object> m = json(data);
            byte[] in = unB64(str(m.get("data")));
            String pem = str(m.get("key")).replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replace("-----BEGIN RSA PUBLIC KEY-----", "")
                    .replace("-----END RSA PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.decode(pem, Base64.DEFAULT);
            java.security.KeyFactory kf = java.security.KeyFactory.getInstance("RSA");
            java.security.PublicKey pk;
            try {
                pk = kf.generatePublic(new java.security.spec.X509EncodedKeySpec(der));
            } catch (Exception e) {
                // PKCS#1 包一层 SubjectPublicKeyInfo
                byte[] spki = concat(hex("30819f300d06092a864886f70d0101010500"), der);
                pk = kf.generatePublic(new java.security.spec.X509EncodedKeySpec(spki));
            }
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, pk);
            return "{\"value\":\"" + b64(cipher.doFinal(in)) + "\"}";
        } catch (Throwable t) {
            return "{\"error\":\"" + jq(String.valueOf(t.getMessage())) + "\"}";
        }
    }

    private byte[] zlib(String data, boolean inflate) throws Exception {
        byte[] in = unB64(str(json(data).get("data")));
        if (inflate) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            for (boolean wrap : new boolean[]{true, false}) { // 先 zlib 带 header, 失败回退 raw
                try {
                    Inflater fl = new Inflater(wrap);
                    fl.setInput(in);
                    byte[] buf = new byte[65536];
                    while (!fl.finished()) {
                        int n = fl.inflate(buf);
                        if (n == 0) {
                            if (fl.needsDictionary() || fl.needsInput()) break;
                        } else {
                            bos.write(buf, 0, n);
                        }
                    }
                    fl.end();
                    return bos.toByteArray();
                } catch (Throwable e) {
                    bos.reset();
                }
            }
            throw new IllegalStateException("inflate failed");
        } else {
            Deflater fl = new Deflater(Deflater.DEFAULT_COMPRESSION, false);
            fl.setInput(in);
            fl.finish();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            while (!fl.finished()) bos.write(buf, 0, fl.deflate(buf));
            fl.end();
            return bos.toByteArray();
        }
    }

    // ---------------- helpers ----------------

    private void evalDispatch(JsEnv env, String action, String payloadJson) {
        // QuickJSContext 有 checkSameThread: 一切 evaluate 必须回 js 线程执行。
        // v27.3 重大bug: 网络回包(okhttp线程)与定时器(主线程)直接 evaluate → 必抛
        // IllegalStateException → 取链结果永远回不到 JS。这里统一 post 回 js 线程。
        final String script = "globalThis.__lx_dispatch__(" + jq(env.key) + "," + jq(action) + "," + jq(payloadJson) + ");";
        final QuickJSContext ctx = env.ctx;
        jsHandler.post(() -> {
            try {
                ctx.evaluate(script);
            } catch (Throwable t) {
                Log.e(TAG, "dispatch " + action, t);
            }
        });
    }

    private void fire(String key, String event, String json) {
        if (listener == null) return;
        mainHandler.post(() -> listener.onEvent(key, event, json));
    }

    private static boolean hasHeader(Map<String, Object> headers, String contains) {
        if (headers == null) return false;
        for (String k : headers.keySet()) {
            if ("content-type".equalsIgnoreCase(k)) {
                String v = String.valueOf(headers.get(k)).toLowerCase();
                if (v.contains(contains)) return true;
            }
        }
        return false;
    }

    private static RequestBody formBody(Map<String, Object> fields) {
        StringBuilder sb = new StringBuilder();
        try {
            if (fields != null) {
                boolean first = true;
                for (Map.Entry<String, Object> e : fields.entrySet()) {
                    if (!first) sb.append('&');
                    first = false;
                    sb.append(java.net.URLEncoder.encode(e.getKey(), "UTF-8")).append('=')
                            .append(java.net.URLEncoder.encode(String.valueOf(e.getValue()), "UTF-8"));
                }
            }
        } catch (Exception ignored) {
        }
        return RequestBody.create(MediaType.parse("application/x-www-form-urlencoded"), sb.toString());
    }

    private static String join(List<String> list, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] hex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(String s) {
        Map<String, Object> out = new HashMap<>();
        try {
            org.json.JSONObject o = new org.json.JSONObject(s);
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                out.put(k, o.get(k));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static String mapToJson(Map<String, Object> m) {
        return new org.json.JSONObject(m).toString();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Object arg(Object[] args, int i) {
        return i < args.length ? args[i] : null;
    }

    private static String b64(byte[] data) {
        return Base64.encodeToString(data, Base64.NO_WRAP);
    }

    private static byte[] unB64(String s) {
        return s == null || s.isEmpty() ? new byte[0] : Base64.decode(s, Base64.DEFAULT);
    }

    private static String jq(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }
}
