
package com.github.catvod.crawler;

import android.content.Context;
import android.util.Log;


import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.MD5;
import com.lzy.okgo.OkGo;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dalvik.system.DexClassLoader;
import okhttp3.Response;

public class JarLoader {
    private final ConcurrentHashMap<String, DexClassLoader> classLoaders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Method> proxyMethods = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Spider> spiders = new ConcurrentHashMap<>();
    // 小贾影视仓 v15.4: 仿 FongMi JarLoader —— spider 创建与 jar 下载的并发锁(防多线程重复 newInstance+init / 同 jar 并发写缓存文件竞争)
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private volatile String recentJarKey = "";
    // 小贾影视仓 v15.9: 内置肥猫 jar 常驻缓存 key(切线路不被逐出) + 当前线路全局 spider jar 的 key 指针
    private static final String BUILTIN_KEY = "feimao_builtin";
    private volatile String mainKey = "";

    /**
     * 不要在主线程调用我
     *
     * @param cache
     */
    public boolean load(String cache) {
        // v15.9: 用 jar 路径 hash 作 key, 使同一 jar 跨线路切换可命中缓存(无需重新 dex/解壳)
        String key = MD5.string2MD5(cache);
        recentJarKey = key;
        mainKey = key;
        return loadClassLoader(cache, key);
    }

    // 小贾影视仓 v15.9: 内置肥猫 jar 专用加载 —— 常驻 BUILTIN_KEY, 切线路不被 clearSpiderCache 逐出,
    // 且只在首次(或 jar 文件变化)真正 dex+Init.init() 解壳, 之后每次切回都秒级复用。
    public boolean loadBuiltin(String cache) {
        recentJarKey = BUILTIN_KEY;
        mainKey = BUILTIN_KEY;
        return loadClassLoader(cache, BUILTIN_KEY);
    }

    public void clear() {
        spiders.clear();
        proxyMethods.clear();
        classLoaders.clear();
        locks.clear();
        mainKey = "";
    }

    // 小贾影视仓: 仅清空"按站点key缓存的 spider 实例", 保留已加载的 jar(classLoader)。
    // 切换线路时调用, 既能让新线路的同名站点拿到正确的 spider(修复搜索无结果),
    // 又不必重新下载/重dex jar(解决切线路慢 + 首页加载不出来)。
    // ⚠️ 必须同时移除 main classLoader: 各线路的全局 spider(jar) 不同, type=3 站点(api=csp_XXX, jar为空→main)
    // 若继续用旧线路的 main jar, 新线路的爬虫类找不到 → 首页空白/搜索无结果(需重启才恢复)。
    // 小贾影视仓 v15.9: 切线路不再逐出已加载的 jar(classLoader)。
    // 改用 mainKey 精确指向"当前线路"的全局 spider jar, 旧线路的 jar 仍缓存在 classLoaders 中(下次切回秒级复用),
    // 既解决"切线路慢/肥猫重新解壳卡顿", 又避免复用错误线路的同名 spider(此前 remove("main") 的动机)。
    // 仅清空: 按站点 key 缓存的 spider 实例 + proxy 方法表 + 复位 key 指针 + 站点级锁。
    // 注: 内置肥猫 jar(BUILTIN_KEY)本就不在此处逐出, 始终常驻。
    public void clearSpiderCache() {
        spiders.clear();
        proxyMethods.clear();
        recentJarKey = "";
        mainKey = "";
        locks.keySet().removeIf(k -> k.startsWith("sp_"));
    }

    private boolean loadClassLoader(String jar, String key) {
        if (classLoaders.containsKey(key)){
            Log.i("JarLoader", "echo-loadClassLoader jar缓存: " + key);
            return true;
        }
        boolean success = false;
        try {
            File cacheDir = new File(App.getInstance().getCacheDir().getAbsolutePath() + "/catvod_csp");
            if (!cacheDir.exists())
                cacheDir.mkdirs();
            final DexClassLoader classLoader = new DexClassLoader(jar, cacheDir.getAbsolutePath(), null, App.getInstance().getClassLoader());
            int count = 0;
            do {
                try {
                    final Class<?> classInit = classLoader.loadClass("com.github.catvod.spider.Init");
                    if (classInit != null) {
                        final Method initMethod = classInit.getMethod("init", Context.class);
                        // 在子线程中调用 init 方法，避免网络请求在主线程中执行
                        Thread initThread = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    initMethod.invoke(null, App.getInstance());
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }
                        });
                        initThread.start();
                        initThread.join();
                        Log.i("JarLoader", "echo-自定义爬虫代码加载成功!");
                        success = true;
                        try {
                            Class<?> proxy = classLoader.loadClass("com.github.catvod.spider.Proxy");
                            Method proxyMethod = proxy.getMethod("proxy", Map.class);
                            proxyMethods.put(key, proxyMethod);
                        } catch (Throwable th) {
                            // 可以记录错误日志
                            th.printStackTrace();
                        }
                        break;
                    }
                    Thread.sleep(200);
                } catch (Throwable th) {
                    th.printStackTrace();
                }
                count++;
            } while (count < 2);

            if (success) {
                classLoaders.put(key, classLoader);
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return success;
    }

    private DexClassLoader loadJarInternal(String jar, String md5, String key) {
        // v15.4: jar 级锁 —— 多站点并发首拉同一独立 jar 时, 只允许一个线程下载/写缓存文件, 其余等待复用
        Object lock = locks.computeIfAbsent("jar_" + key, k -> new Object());
        synchronized (lock) {
            if (classLoaders.containsKey(key)) {
                Log.i("JarLoader", "echo-loadJarInternal jar缓存: " + key);
                return classLoaders.get(key);
            }
            File cache = new File(App.getInstance().getFilesDir().getAbsolutePath() + "/csp/" + key + ".jar");
            if (!md5.isEmpty()) {
                if (cache.exists() && MD5.getFileMd5(cache).equalsIgnoreCase(md5)) {
                    if (loadClassLoader(cache.getAbsolutePath(), key)) {
                        return classLoaders.get(key);
                    } else {
                        return null;
                    }
                }
            } else {
                if (cache.exists() && !FileUtils.isWeekAgo(cache)) {
                    if (loadClassLoader(cache.getAbsolutePath(), key)) {
                        return classLoaders.get(key);
                    }
                }
            }
            try {
                Response response = OkGo.<File>get(jar).execute();
                assert response.body() != null;
                InputStream is = response.body().byteStream();
                OutputStream os = new FileOutputStream(cache);
                try {
                    byte[] buffer = new byte[2048];
                    int length;
                    while ((length = is.read(buffer)) > 0) {
                        os.write(buffer, 0, length);
                    }
                } finally {
                    try {
                        is.close();
                        os.close();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                loadClassLoader(cache.getAbsolutePath(), key);
                return classLoaders.get(key);
            } catch (Throwable e) {
                e.printStackTrace();
            }
            return null;
        }
    }

    public Spider getSpider(String key, String cls, String ext, String jar) {
        if (spiders.containsKey(key)) {
            Log.i("JarLoader", "echo-getSpider spider缓存: " + key);
            return spiders.get(key);
        }
        // v15.4: 并发双检 —— 搜索等多站并发首拉同站时, 只允许一个线程建 spider+init(ext), 其余等待复用
        Object lock = locks.computeIfAbsent("sp_" + key, k -> new Object());
        synchronized (lock) {
            if (spiders.containsKey(key)) {
                return spiders.get(key);
            }
            String clsKey = cls.replace("csp_", "");
            String jarUrl = "";
            String jarMd5 = "";
            String jarKey;
            if (jar.isEmpty()) {
                // v15.9: 空 jar 站点依赖"当前线路全局 spider jar" —— 用 mainKey 精确指向, 缺失时回退内置肥猫常驻 jar
                jarKey = mainKey.isEmpty() ? BUILTIN_KEY : mainKey;
            } else {
                String[] urls = jar.split(";md5;");
                jarUrl = urls[0];
                jarKey = MD5.string2MD5(jarUrl);
                jarMd5 = urls.length > 1 ? urls[1].trim() : "";
            }
            recentJarKey = jarKey;
            assert jarKey != null;
            DexClassLoader classLoader = jar.isEmpty()
                    ? classLoaders.get(jarKey)
                    : loadJarInternal(jarUrl, jarMd5, jarKey);
            // 兜底: 切换瞬间 mainKey 暂空, 若内置肥猫已常驻则回退(避免拿到 SpiderNull 导致首页/推荐空白)
            if (classLoader == null && jar.isEmpty()) {
                classLoader = classLoaders.get(BUILTIN_KEY);
            }
            if (classLoader == null) return new SpiderNull();
            try {
                Log.i("JarLoader", "echo-getSpider 加载spider: " + key);
                Spider sp = (Spider) classLoader.loadClass("com.github.catvod.spider." + clsKey).newInstance();
                sp.init(App.getInstance(), ext);
                // v15.1: 移除原 homeContent(false) 预热 —— 带 jar 站点首次创建 spider 时多打一次首页请求,
                // 与随后 getSort 的 homeContent(true) 重复, 是"切线路后首页加载慢"的重要成因之一。
                spiders.put(key, sp);
                return sp;
            } catch (Throwable th) {
                th.printStackTrace();
            }
            return new SpiderNull();
        }
    }

    public JSONObject jsonExt(String key, LinkedHashMap<String, String> jxs, String url) {
        try {
            DexClassLoader classLoader = classLoaders.get(mainKey.isEmpty() ? BUILTIN_KEY : mainKey);
            if (classLoader == null) classLoader = classLoaders.get(BUILTIN_KEY);
            if (classLoader == null) return null;
            String clsKey = "Json" + key;
            String hotClass = "com.github.catvod.parser." + clsKey;
            assert classLoader != null;
            Class<?> jsonParserCls = classLoader.loadClass(hotClass);
            Method mth = jsonParserCls.getMethod("parse", LinkedHashMap.class, String.class);
            return (JSONObject) mth.invoke(null, jxs, url);
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return null;
    }

    public JSONObject jsonExtMix(String flag, String key, String name, LinkedHashMap<String, HashMap<String, String>> jxs, String url) {
        try {
            DexClassLoader classLoader = classLoaders.get(mainKey.isEmpty() ? BUILTIN_KEY : mainKey);
            if (classLoader == null) classLoader = classLoaders.get(BUILTIN_KEY);
            if (classLoader == null) return null;
            String clsKey = "Mix" + key;
            String hotClass = "com.github.catvod.parser." + clsKey;
            assert classLoader != null;
            Class<?> jsonParserCls = classLoader.loadClass(hotClass);
            Method mth = jsonParserCls.getMethod("parse", LinkedHashMap.class, String.class, String.class, String.class);
            return (JSONObject) mth.invoke(null, jxs, name, flag, url);
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return null;
    }

    public Object[] proxyInvoke(Map<String,String> params) {
        try {
            Method proxyFun = proxyMethods.get(recentJarKey);
            if (proxyFun == null && !mainKey.isEmpty()) proxyFun = proxyMethods.get(mainKey);
            if (proxyFun == null) proxyFun = proxyMethods.get(BUILTIN_KEY);
            if (proxyFun != null) {
                return (Object[]) proxyFun.invoke(null, params);
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return null;
    }
}
