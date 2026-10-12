package com.undcover.freedom.pyramid;

import android.app.Application;
import android.content.Context;
import android.util.Base64;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import com.github.catvod.crawler.Spider;

import com.github.tvbox.osc.util.OkGoHelper;
import com.github.tvbox.osc.util.urlhttp.OKCallBack;
import com.github.tvbox.osc.util.urlhttp.OkHttpUtil;


import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import okhttp3.Call;
import okhttp3.Response;

public class PythonLoader {
    private final ConcurrentHashMap<String, Spider> spiders = new ConcurrentHashMap<>();
    // 小贾影视仓 v23.8: 正在初始化中的 spider 去重表 —— 同一个 py 源并发取 Spider 时只初始化一次,
    // 后续调用都等同一个 Future(旧实现每次都新建线程池重新 init, 超时还不缓存 => 反复白跑 10 秒)。
    private final ConcurrentHashMap<String, Future<PythonSpider>> spiderInits = new ConcurrentHashMap<>();
    private static PythonLoader sInstance;
    private Application app;
    private final HashMap<String, JSONObject> siteMap;
    Python pyInstance;
    PyObject pyApp;
    Python.Platform androidPlatform;
    // v23.8: Python 运行时后台预热 —— 原实现 setApplication() 在构造器(ApiConfig 字段初始化,
    // 主线程)里同步 Python.start() + getModule("app"), 冷启动直接卡死主线程一两秒,
    // 且首次 py 调用必然慢。现在启动动作丢到后台线程, getSpider 时再等它就绪。
    private final ExecutorService bootExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "py-boot");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean bootStarted = false;
    private volatile boolean pyReady = false;
    private volatile Throwable bootError = null;

    public PythonLoader() {
        siteMap = new HashMap<>();
    }

    public static PythonLoader getInstance() {
        if (sInstance == null) {
            synchronized (PyToast.class) {
                if (sInstance == null) {
                    sInstance = new PythonLoader();
                }
            }
        }
        return sInstance;
    }

    private void setSdk(Context context) {
        int logLevel = PyLog.LEVEL_V;
        PyLog.getInstance().setLogLevel(logLevel).setFilter(PyLog.FILTER_NW | PyLog.FILTER_LC);
        PyLog.TagConstant.TAG_APP = "PythonLoader";

        PyToast.init(context);
    }

    public void setConfig(String config) {
        try {
            JSONObject configJo = new JSONObject(config);
            JSONArray siteList = configJo.getJSONArray("sites");
            // 小贾影视仓 v23.8: 每次解析新线路先清空旧表, 防止多条线路的同名 api/ext 互相串台
            siteMap.clear();
            for (int i = 0; i < siteList.length(); i++) {
                JSONObject jo = siteList.getJSONObject(i);
                String key = jo.optString("api");
                siteMap.put(key, jo);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public PythonLoader setApplication(Application app) {
        this.app = app;
        setSdk(this.app);
        startBoot();
        return this;
    }

    /**
     * 小贾影视仓 v23.8: Python 运行时后台预热(不卡主线程)。
     * Chaquopy 的 Python.start 首次要走 dex 提取/so 加载, 旧实现放在构造器链路(主线程)里同步做,
     * 首页/线路切换都会被拖慢。这里改成后台单线程执行, getSpider 时用 awaitBoot 等它就绪。
     */
    private void startBoot() {
        if (pyReady || bootStarted) return;
        synchronized (this) {
            if (pyReady || bootStarted) return;
            bootStarted = true;
        }
        bootExecutor.execute(() -> {
            try {
                if (!Python.isStarted()) {
                    androidPlatform = new AndroidPlatform(app);
                    Python.start(androidPlatform);
                }
                pyInstance = Python.getInstance();
                pyApp = pyInstance.getModule("app");
                pyReady = true;
                PyLog.d("Python 运行时后台预热完成");
            } catch (Throwable th) {
                bootError = th;
                PyLog.e("Python 运行时启动失败: " + th.getMessage());
            }
        });
    }

    /** 等 Python 运行时就绪, 最多 timeoutSec 秒。失败/超时返回 false。 */
    private boolean awaitBoot(long timeoutSec) {
        if (pyReady) return true;
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        while (!pyReady && System.currentTimeMillis() < deadline) {
            if (bootError != null) return false;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return pyReady;
    }

    String cache = "/storage/emulated/0/plugin/";

    // 小贾影视仓 v19: 插件目录改为"外部优先 + 内部兜底"。
    // 原实现硬编码 /storage/emulated/0/plugin/, 在未授予存储权限(或 Android 11+ 分区存储受限)时不可写,
    // downloadPlugin 会失败 -> py spider 静默失效(表现为"全能版装了但 .py 用不了")。
    // 应用私有目录 filesDir 始终可写, 且 Chaquopy 的 Python 与本进程同权限可直接访问, 作为兜底可确保可用。
    private String pluginDir() {
        try {
            java.io.File ext = new java.io.File(cache != null && !cache.isEmpty() ? cache : "/storage/emulated/0/plugin/");
            if ((ext.exists() || ext.mkdirs()) && ext.isDirectory() && ext.canWrite()) {
                return ext.getAbsolutePath() + "/";
            }
        } catch (Throwable ignored) { }
        if (app != null) {
            try {
                java.io.File in = new java.io.File(app.getFilesDir(), "plugin/");
                in.mkdirs();
                return in.getAbsolutePath() + "/";
            } catch (Throwable ignored) { }
        }
        return cache;
    }

    public PythonLoader setPluginConfig(String config) {
        this.cache = config;
        return this;
    }

    public String getUrlByApi(String api) {
        String key = "";
        String url = "";
        if (siteMap.containsKey(api)) {
            JSONObject jo = siteMap.get(api);
            key = jo.optString("key");
            url = jo.optString("ext");
        }
        if (!key.isEmpty() && !url.isEmpty()) {
            if (spiders.containsKey(key)) {
                return "";
            } else {
                return url;
            }
        }
        return "";
    }

    public Spider getSpider(String key, String url) throws Exception {
        if (app == null) throw new Exception("set application first");
        Spider cached = spiders.get(key);
        if (cached != null) {
            PyLog.d(key + " :缓存加载成功！");
            return cached;
        }
        // 小贾影视仓 v23.8: 先等 Python 运行时后台预热完成(最多 30s),
        // 再等 spider 初始化(下载插件+加载依赖+init, 网络慢时 10s 根本不够 => 提到 25s)。
        if (!awaitBoot(30)) {
            throw new Exception(bootError == null ? "python runtime boot timeout" : bootError.toString());
        }
        Future<PythonSpider> f = spiderInits.get(key);
        if (f == null) {
            java.util.concurrent.FutureTask<PythonSpider> task =
                    new java.util.concurrent.FutureTask<>(() -> {
                        PythonSpider sp = new PythonSpider(key, pluginDir());
                        sp.init(app, url);
                        return sp;
                    });
            f = spiderInits.putIfAbsent(key, task);
            if (f == null) {
                f = task;
                bootExecutor.execute(task); // 复用同一个单线程执行器: Python 调用串行化, 避免资源挤兑
            }
        }
        try {
            PythonSpider sp = f.get(25, TimeUnit.SECONDS);
            spiders.put(key, sp);
            spiderInits.remove(key, f);
            PyLog.d(key + " :初始化成功");
            return sp;
        } catch (TimeoutException e) {
            PyLog.e(key + " :init 超时(25s), 后台继续加载, 下次调用可直接等结果");
            // 不缓存失败结果 —— 上层抛异常走"源不可用"处理, 用户重试时无需推倒重来
            throw new Exception("py init timeout: " + key);
        } catch (ExecutionException e) {
            spiderInits.remove(key, f);
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            PyLog.e(key + " :init 失败 " + cause.getMessage());
            throw new Exception(cause);
        }
    }

    int port = -1;

    public void getPort() {
        if (port > 0) return;
        for (int i = 9978; i < 10000; i++) {
            try {
                if (OkHttpUtil.string("http://127.0.0.1:" + i + "/proxy?do=ck&api=python", null).equals("ok")) {
                    port = i;
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        // 小贾影视仓 v23.3: 原实现在全部探测失败时 port 仍是 -1 且不重试,
        // localProxyUrl() 会返回 "http://127.0.0.1:-1/proxy" 这种废地址,
        // py 源换算出来的播放地址直接失效。这里兜底用默认端口, 并允许下次重试。
        if (port <= 0) {
            port = 9978;
        }
    }

    public String localProxyUrl() {
        getPort();
        return "http://127.0.0.1:" + port + "/proxy";
    }

    public Map<String, String> str2map(String header) {
        Map<String, String> map = new HashMap<>();
        if (header == null || header.isEmpty())
            return map;
        try {
            JSONObject jo = new JSONObject(header);
            for (Iterator<String> it = jo.keys(); it.hasNext(); ) {
                String key = it.next();
                String value = jo.optString(key);
                map.put(key, value);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return map;
    }

    public InputStream getFileStream(String url, String param, String header) {
        if (streamCallback != null) {
            return streamCallback.get(url, str2map(param), str2map(header));
        } else {
            OKCallBack.OKCallBackDefault callBack = new OKCallBack.OKCallBackDefault() {
                @Override
                protected void onFailure(Call call, Exception e) {

                }

                @Override
                protected void onResponse(Response response) {

                }
            };
            OkHttpUtil.get(OkGoHelper.getDefaultClient(), url, str2map(param), str2map(header), callBack);
            return callBack.getResult().body().byteStream();
        }
    }

    public String getFileString(String url, String header) {
        if (stringCallback != null) {
            return stringCallback.get(url, str2map(header));
        } else {
            return OkHttpUtil.string(url, str2map(header));
        }
    }

    FileStreamCallback streamCallback;
    FileStringCallback stringCallback;

    public PythonLoader setFileStreamCallback(FileStreamCallback callback) {
        streamCallback = callback;
        return this;
    }

    public PythonLoader setFileStringCallback(FileStringCallback callback) {
        stringCallback = callback;
        return this;
    }

    public interface FileStreamCallback {
        InputStream get(String url, Map<String, String> paramsMap, Map<String, String> headerMap);
    }

    public interface FileStringCallback {
        String get(String url, Map<String, String> headerMap);
    }
}
