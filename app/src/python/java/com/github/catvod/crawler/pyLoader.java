// 文件: app/src/python/java/com/github/catvod/crawler/python/PyLoader.java
package com.github.catvod.crawler;

import android.Manifest;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.github.catvod.crawler.python.IPyLoader;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;
import com.undcover.freedom.pyramid.PythonLoader;
import com.undcover.freedom.pyramid.PythonSpider;
import java.io.UnsupportedEncodingException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class pyLoader implements IPyLoader {
    private final PythonLoader pythonLoader;
    private final ConcurrentHashMap<String, Spider> spiders;
    private String lastConfig = null; // 记录上次的配置

    public pyLoader() {
        pythonLoader = PythonLoader.getInstance().setApplication(App.getInstance());
        spiders = new ConcurrentHashMap<>();
    }

    @Override
    public void clear() {
        spiders.clear();
    }

    @Override
    public void setConfig(String jsonStr) {
        if (jsonStr != null && !jsonStr.equals(lastConfig)) {
            Log.i("PyLoader", "echo-setConfig 初始化json ");
            pythonLoader.setConfig(jsonStr);
            lastConfig = jsonStr;
        }
    }

    private String recentPyApi;
    @Override
    public void setRecentPyKey(String pyApi) {
        recentPyApi = pyApi;
    }

    @Override
    public Spider getSpider(String key, String cls, String ext) {
        if (spiders.containsKey(key)) {
            Log.i("PyLoader", "echo-getSpider spider缓存: " + key);
            return spiders.get(key);
        }
        try {
            // 小贾影视仓 v19: 不再因缺 WRITE_EXTERNAL_STORAGE 直接返回 SpiderNull。
            // PythonLoader 的插件目录已支持"外部优先 + filesDir 内部兜底", 内部目录无需该权限即可写;
            // 原实现在未授权时静默放弃, 是"本地 .py 用不了"的直接原因之一。
            if (ContextCompat.checkSelfPermission(App.getInstance(), Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                Log.i("PyLoader", "无外部存储权限, 继续尝试(将使用应用内部插件目录)");
            }
            Log.i("PyLoader", "echo-getSpider url: " + getPyUrl(cls, ext));
            Spider sp = pythonLoader.getSpider(key, getPyUrl(cls, ext));
//            Log.i("PyLoader", "echo-getSpider homeContent: " + sp.homeContent(true));
            spiders.put(key, sp);
            Log.i("PyLoader", "echo-getSpider 加载spider: " + key);
            return sp;
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return new SpiderNull();
    }

    @Override
    public Object[] proxyInvoke(Map<String, String> params){
        // 小贾影视仓 v23.3: 本机中转(localProxy)是 py 源播放的必要环节 ——
        // qimao.py 的「🛡中转线路」靠它把 m3u8 分片绝对化后吐给播放器。
        // 原实现第一行 `if(recentPyApi==null) return null;` 且 setRecentPyKey 全工程无人调用,
        // 导致 recentPyApi 恒为 null → 中转永远拿不到 spider → 返回 null → 播放地址为空。
        // 现由 ApiConfig.getCSP() 在取 py 源时写入 recentPyApi。
        if (recentPyApi == null || recentPyApi.isEmpty()) {
            LOG.i("echo-proxyInvoke: recentPyApi 为空, 无法定位 py spider(本机中转不可用)");
            return null;
        }
        LOG.i("echo-recentPyApi" + recentPyApi);
        try {
            Spider sp = getSpider(MD5.string2MD5(recentPyApi), recentPyApi, "");
            if (sp instanceof PythonSpider) {
                return ((PythonSpider) sp).proxyLocal(params);
            }
            LOG.i("echo-proxyInvoke: spider 不是 PythonSpider, 无法中转");
        } catch (Throwable th) {
            LOG.i("echo-proxyInvoke_Throwable:---" + th.getMessage());
            th.printStackTrace();
        }
        return null;
    }

    private String getPyUrl(String api, String ext) throws UnsupportedEncodingException {
        StringBuilder urlBuilder = new StringBuilder(api);
        if (!ext.isEmpty()) {
//            ext= URLEncoder.encode(ext,"utf8");
            urlBuilder.append(api.contains("?") ? "&" : "?").append("extend=").append(ext);
        }
        return urlBuilder.toString();
    }
}
