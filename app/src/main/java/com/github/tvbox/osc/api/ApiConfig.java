package com.github.tvbox.osc.api;

import static com.github.tvbox.osc.util.RegexUtils.getPattern;
import android.app.Activity;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;

import com.github.catvod.crawler.JarLoader;
import com.github.catvod.crawler.JsLoader;
import com.github.catvod.crawler.pyLoader;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.python.IPyLoader;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.IJKCode;
import com.github.tvbox.osc.bean.LiveChannelGroup;
import com.github.tvbox.osc.bean.LiveChannelItem;
import com.github.tvbox.osc.bean.ParseBean;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.ui.activity.HomeActivity;
import com.github.tvbox.osc.util.AES;
import com.github.tvbox.osc.util.AdBlocker;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.M3U8;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.VideoParseRuler;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.orhanobut.hawk.Hawk;

import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.tvbox.osc.util.LOG;
/**
 * @author pj567
 * @date :2020/12/18
 * @description:
 */
public class ApiConfig {
    private static ApiConfig instance;
    private final LinkedHashMap<String, SourceBean> sourceBeanList;
    private SourceBean mHomeSource;
    private String lastApiUrl = "";   // 小贾影视仓: 记录上次加载的线路, 用于切换线路时重置首页源
    // 小贾影视仓: 参考豆瓣(首页推荐"锁死豆瓣"用)——从任一含豆瓣的线路捕获, 供无豆瓣的线路注入
    private static final String HAWK_REF_DOUBAN = "xiaojia_ref_douban";
    private SourceBean referenceDouban = null;
    private String referenceDoubanSpider = "";
    private ParseBean mDefaultParse;
    private final List<LiveChannelGroup> liveChannelGroupList;
    private final List<ParseBean> parseBeanList;
    private List<String> vipParseFlags;
    private List<IJKCode> ijkCodes;
    private String spider = null;
    public String wallpaper = "";
    public JsonArray livePlayHeaders;
    private final SourceBean emptyHome = new SourceBean();

    private final JarLoader jarLoader = new JarLoader();
    private final JsLoader jsLoader = new JsLoader();
    private final IPyLoader pyLoader =  new pyLoader();
    private final String userAgent = "okhttp/3.15";

    private final String requestAccept = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9";

    private ApiConfig() {
        clearLoader();
        sourceBeanList = new LinkedHashMap<>();
        liveChannelGroupList = new ArrayList<>();
        parseBeanList = new ArrayList<>();
    }

    public static ApiConfig get() {
        if (instance == null) {
            synchronized (ApiConfig.class) {
                if (instance == null) {
                    instance = new ApiConfig();
                }
            }
        }
        return instance;
    }

    public static String FindResult(String json, String configKey) {
        String content = json;
        try {
            if (AES.isJson(content)) return content;
            Pattern pattern = getPattern("[A-Za-z0]{8}\\*\\*");
            Matcher matcher = pattern.matcher(content);
            if (matcher.find()) {
                content = content.substring(content.indexOf(matcher.group()) + 10);
                content = new String(Base64.decode(content, Base64.DEFAULT));
            }
            if (content.startsWith("2423")) {
                String data = content.substring(content.indexOf("2324") + 4, content.length() - 26);
                content = new String(AES.toBytes(content)).toLowerCase();
                String key = AES.rightPadding(content.substring(content.indexOf("$#") + 2, content.indexOf("#$")), "0", 16);
                String iv = AES.rightPadding(content.substring(content.length() - 13), "0", 16);
                json = AES.CBC(data, key, iv);
            } else if (configKey != null && !AES.isJson(content)) {
                json = AES.ECB(content, configKey);
            } else {
                json = content;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return json;
    }

    private static byte[] getImgJar(String body) {
        Pattern pattern = getPattern("[A-Za-z0]{8}\\*\\*");
        Matcher matcher = pattern.matcher(body);
        if (matcher.find()) {
            body = body.substring(body.indexOf(matcher.group()) + 10);
            return Base64.decode(body, Base64.DEFAULT);
        }
        return "".getBytes();
    }

    // 小贾影视仓: 默认线路(图片/失效)加载失败时自动回退的内置可用 JSON 线路, 保证首页一定能出来
    // 顺序按"资源多少"排: newwex(88站,资源全) > 张群(19站) > HGYX(资源少,垫底)
    private static final String[] FALLBACK_LINES = new String[]{
            "https://9280.kstore.vip/newwex.json",
            "https://zhangqun1818.serv00.net/zq/api.json",
            "https://api.hgyx.vip/hgyx.json"
    };

    public void loadConfig(boolean useCache, LoadConfigCallback callback, Activity activity) {
        // 小贾影视仓: 加载参考豆瓣(首页锁死豆瓣用)
        loadReferenceDouban();
        String apiUrl = Hawk.get(HawkConfig.API_URL, HomeActivity.getRes().getString(R.string.app_source));
        loadConfigUrl(useCache, apiUrl, callback, activity, false);
    }

    private void loadConfigUrl(boolean useCache, String apiUrl, LoadConfigCallback callback, Activity activity, boolean isFallback) {
        // Embedded Source : Update in Strings.xml if required
        if (apiUrl == null || apiUrl.isEmpty()) {
            callback.error("源地址为空");
            return;
        }
        // 小贾影视仓: 支持本地接口包(file:// 前缀 或 存在的本地文件路径), 方便本地调试/离线使用
        if (apiUrl.startsWith("file://") || apiUrl.startsWith("/") || apiUrl.matches("^[a-zA-Z]:[\\\\/].*")) {
            try {
                String path = apiUrl.startsWith("file://") ? apiUrl.substring("file://".length()) : apiUrl;
                java.io.File lf = new java.io.File(path);
                if (lf.exists()) {
                    parseJson(apiUrl, lf);
                    callback.success();
                    return;
                } else {
                    callback.error("本地配置不存在: " + path);
                    return;
                }
            } catch (Throwable th) {
                th.printStackTrace();
                callback.error("解析本地配置失败");
                return;
            }
        }
        File cache = new File(App.getInstance().getFilesDir().getAbsolutePath() + "/" + MD5.encode(apiUrl));
        // 小贾影视仓 v15.2: clan 线路(clan:// 本地包/内置包)不走磁盘缓存 —— 本地回环几乎零延迟,
        // 且缓存里固化的是当时 serverPort 的绝对地址, 下次端口变化会读成死链接
        if (useCache && cache.exists() && !apiUrl.startsWith("clan")) {
            try {
                parseJson(apiUrl, cache);
                callback.success();
                return;
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
        String TempKey = null, configUrl = "", pk = ";pk;";
        if (apiUrl.contains(pk)) {
            String[] a = apiUrl.split(pk);
            TempKey = a[1];
            if (apiUrl.startsWith("clan")) {
                configUrl = clanToAddress(a[0]);
            } else if (apiUrl.startsWith("http")) {
                configUrl = a[0];
            } else {
                configUrl = "http://" + a[0];
            }
        } else if (apiUrl.startsWith("clan")) {
            configUrl = clanToAddress(apiUrl);
        } else if (!apiUrl.startsWith("http")) {
            configUrl = "http://" + configUrl;
        } else {
            configUrl = apiUrl;
        }
        System.out.println("API URL :" + configUrl);
        String configKey = TempKey;
        OkGo.<String>get(configUrl)
                .headers("User-Agent", userAgent)
                .headers("Accept", requestAccept)
                .execute(new AbsCallback<String>() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        try {
                            String json = response.body();
                            parseJson(apiUrl, json);
                            try {
                                File cacheDir = cache.getParentFile();
                                if (!cacheDir.exists())
                                    cacheDir.mkdirs();
                                if (cache.exists())
                                    cache.delete();
                                FileOutputStream fos = new FileOutputStream(cache);
                                fos.write(json.getBytes("UTF-8"));
                                fos.flush();
                                fos.close();
                            } catch (Throwable th) {
                                th.printStackTrace();
                            }
                            callback.success();
                        } catch (Throwable th) {
                            th.printStackTrace();
                            // 小贾影视仓: 默认线路解析失败(如图片配置 itv666.webp), 同样回退到可用 JSON 线路
                            if (!isFallback) {
                                String def = "";
                                if (HomeActivity.getRes() != null)
                                    def = HomeActivity.getRes().getString(R.string.app_source);
                                if (apiUrl.equals(def)) {
                                    tryFallback(0, useCache, callback, activity);
                                    return;
                                }
                            }
                            callback.error("解析配置失败");
                        }
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        if (cache.exists()) {
                            try {
                                parseJson(apiUrl, cache);
                                callback.success();
                                return;
                            } catch (Throwable th) {
                                th.printStackTrace();
                            }
                        }
                        // v15.1: 网络拉取失败且本线路存在已知镜像时, 自动用镜像重拉一次(如 饭太硬.art→.net)。
                        // 只处理"换域名可救"的网络错误; 镜像地址自身不再命中映射表, 不会死循环。
                        if (!isFallback && apiUrl != null) {
                            String mirror = getLineMirror(apiUrl);
                            if (mirror != null && !mirror.equals(apiUrl)) {
                                loadConfigUrl(false, mirror, callback, activity, true);
                                return;
                            }
                        }
                        // 小贾影视仓: 默认线路(图片/失效)加载失败时, 自动回退到可用的 JSON 线路, 保证首页一定能出来
                        if (!isFallback) {
                            String def = "";
                            if (HomeActivity.getRes() != null)
                                def = HomeActivity.getRes().getString(R.string.app_source);
                            if (apiUrl.equals(def)) {
                                tryFallback(0, useCache, callback, activity);
                                return;
                            }
                        }
                        callback.error("拉取配置失败\n" + (response.getException() != null ? response.getException().getMessage() : ""));
                    }

                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        String result = "";
                        if (response.body() == null) {
                            result = "";
                        } else {
                            byte[] raw = response.body().bytes();
                            // 小贾影视仓: 支持图片配置(如 itv666.cc/aowu/config.webp 把 base64 配置藏在图片后面)
                            if (isImageConfig(raw)) {
                                String imgCfg = extractImageConfig(raw);
                                if (imgCfg != null && !imgCfg.isEmpty()) {
                                    result = fixContentPath(apiUrl, imgCfg);
                                    return result;
                                }
                            }
                            String text = new String(raw, "UTF-8");
                            result = FindResult(text, configKey);
                        }
                        if (apiUrl.startsWith("clan")) {
                            result = clanContentFix(clanToAddress(apiUrl), result);
                        }
                        result = fixContentPath(apiUrl, result);
                        return result;
                    }
                });
    }

    // 小贾影视仓 v15.1: 线路镜像映射表(域名级防封/容灾)。网络拉取失败时自动换镜像重拉一次。
    // 镜像地址不会再命中本表(表内不互为镜像), 不会死循环。
    private static String getLineMirror(String url) {
        if (url == null) return null;
        String[][] mirrors = new String[][]{
                {"http://www.饭太硬.art/tv", "http://www.饭太硬.net/tv"},
                {"http://饭太硬.art/tv", "http://www.饭太硬.net/tv"},
                {"https://www.饭太硬.art/tv", "http://www.饭太硬.net/tv"},
                {"http://www.饭太硬.net/tv", "http://www.饭太硬.art/tv"}
        };
        for (String[] m : mirrors) {
            if (url.equals(m[0])) return m[1];
        }
        return null;
    }

    // 小贾影视仓: 依次尝试回退线路; 成功则把生效线路记为当前 API_URL(标题与内容一致), 全部失败才报错
    private void tryFallback(int index, boolean useCache, LoadConfigCallback callback, Activity activity) {
        if (index >= FALLBACK_LINES.length) {
            callback.error("所有内置线路均加载失败，请检查网络或切换其它线路");
            return;
        }
        final String fb = FALLBACK_LINES[index];
        loadConfigUrl(useCache, fb, new LoadConfigCallback() {
            @Override
            public void success() {
                Hawk.put(HawkConfig.API_URL, fb);
                callback.success();
            }

            @Override
            public void retry() {
                callback.retry();
            }

            @Override
            public void error(String msg) {
                tryFallback(index + 1, useCache, callback, activity);
            }
        }, activity, true);
    }

    public void loadJar(boolean useCache, String spider, LoadConfigCallback callback) {
        String[] urls = spider.split(";md5;");
        String jarUrl = urls[0];
        String md5 = urls.length > 1 ? urls[1].trim() : "";
        File cache = new File(App.getInstance().getFilesDir().getAbsolutePath() + "/csp/"+MD5.string2MD5(jarUrl)+".jar");
        if (!md5.isEmpty() || useCache) {
            if (cache.exists() && (useCache || MD5.getFileMd5(cache).equalsIgnoreCase(md5))) {
                if (jarLoader.load(cache.getAbsolutePath())) {
                    callback.success();
                } else {
                    callback.error("从缓存加载jar失败");
                }
                return;
            }
        }else {
            if (Boolean.parseBoolean(jarCache) && cache.exists() && !FileUtils.isWeekAgo(cache)) {
                if (jarLoader.load(cache.getAbsolutePath())) {
                    callback.success();
                    return;
                }
            }
        }

        boolean isJarInImg = jarUrl.startsWith("img+");
        jarUrl = jarUrl.replace("img+", "");
        OkGo.<File>get(jarUrl)
                .headers("User-Agent", userAgent)
                .headers("Accept", requestAccept)
                .execute(new AbsCallback<File>() {

                    @Override
                    public File convertResponse(okhttp3.Response response){
                        File cacheDir = cache.getParentFile();
                        assert cacheDir != null;
                        if (!cacheDir.exists()) cacheDir.mkdirs();
                        if (cache.exists()) cache.delete();
                        // 3. 使用 try-with-resources 确保流关闭
                        assert response.body() != null;
                        try (FileOutputStream fos = new FileOutputStream(cache)) {
                            if (isJarInImg) {
                                String respData = response.body().string();
                                LOG.i("echo---jar Response: " + respData);
                                byte[] imgJar = getImgJar(respData);
                                if (imgJar == null || imgJar.length == 0) {
                                    LOG.e("echo---Generated JAR data is empty");
                                    callback.error("JAR data is empty");
                                }
                                fos.write(imgJar);
                            } else {
                                // 使用流式传输避免内存溢出
                                InputStream inputStream = response.body().byteStream();
                                byte[] buffer = new byte[4096];
                                int bytesRead;
                                while ((bytesRead = inputStream.read(buffer)) != -1) {
                                    fos.write(buffer, 0, bytesRead);
                                }
                            }
                            fos.flush();
                        } catch (IOException e) {
                            return null;
                        }
                        return cache;
                    }

                    @Override
                    public void onSuccess(Response<File> response) {
                        File file = response.body();
                        if (file != null && file.exists()) {
                            try {
                                if (jarLoader.load(file.getAbsolutePath())) {
                                    callback.success();
                                } else {
                                    LOG.e("echo---jar Loader returned false");
                                    callback.error("从网络上加载jar写入缓存后加载失败");
                                }
                            } catch (Exception e) {
                                LOG.e("echo---jar Loader threw exception: " + e.getMessage());
                                callback.error("JAR加载异常: " + e.getMessage());
                            }
                        } else {
                            LOG.e("echo---jar File not found");
                            callback.error("从网络上加载jar地址字节数据为空");
                        }
                    }

                    @Override
                    public void onError(Response<File> response) {
                        Throwable ex = response.getException();
                        if (ex != null) {
                            LOG.i("echo---jar Request failed: " + ex.getMessage());
                        }
                        // v15: 网络拉 jar 失败时, 只有缓存文件 md5 与期望一致(或该 jar 无 md5 要求)才允许顶替。
                        // 否则会把旧线路/损坏的 jar 当 main, 导致新线路所有 type=3 站从错误 jar 找类 → 首页/搜索空白。
                        // v15.1: 缓存 md5 匹配且能成功 dex 时视为"加载成功"(静默), 不再向 UI 报错 ——
                        // 修复"jar 网络源不可达但本地缓存完好"时仍弹『拉取失败』、首页起不来的体验问题。
                        if (cache.exists()) {
                            boolean usable;
                            if (md5.isEmpty()) usable = true;
                            else usable = MD5.getFileMd5(cache).equalsIgnoreCase(md5);
                            if (usable) {
                                try {
                                    if (jarLoader.load(cache.getAbsolutePath())) {
                                        callback.success();
                                        return;
                                    }
                                } catch (Throwable th) {
                                    th.printStackTrace();
                                }
                            }
                        }
                        callback.error(ex != null ? "从网络上加载jar失败：" + ex.getMessage() : "未知网络错误");
                    }
                });
    }

    private void parseJson(String apiUrl, File f) throws Throwable {
        System.out.println("从本地缓存加载" + f.getAbsolutePath());
        BufferedReader bReader = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String s = "";
        while ((s = bReader.readLine()) != null) {
            sb.append(s + "\n");
        }
        bReader.close();
        parseJson(apiUrl, sb.toString());
    }

    private static  String jarCache ="true";
    private void parseJson(String apiUrl, String jsonStr) {
        // 小贾影视仓: 切换线路时清空旧站点, 防止多个线路的站点累积混在一起
        sourceBeanList.clear();
//        pyLoader.setConfig(jsonStr);
        // 小贾影视仓: 去掉配置里的 // 与 /* */ 注释(不少源如 hgyx 带注释), 否则 Gson 解析直接失败
        jsonStr = stripJsonComments(jsonStr);
        JsonObject infoJson = new Gson().fromJson(jsonStr, JsonObject.class);
        jarCache = DefaultConfig.safeJsonString(infoJson, "jarCache", "true");
        // spider
        spider = DefaultConfig.safeJsonString(infoJson, "spider", "");
        // wallpaper
        wallpaper = DefaultConfig.safeJsonString(infoJson, "wallpaper", "");
        // 直播播放请求头
        livePlayHeaders = infoJson.getAsJsonArray("livePlayHeaders");
        // 远端站点源
        // 小贾影视仓: 额外记录"第一个非 meta 的内容站", 避免兜底时把公告/配置站当首页(导致"推荐消失")
        SourceBean firstSite = null;
        SourceBean firstContentSite = null;
        JsonArray sites = infoJson.has("video") ? infoJson.getAsJsonObject("video").getAsJsonArray("sites") : infoJson.get("sites").getAsJsonArray();
        for (JsonElement opt : sites) {
            JsonObject obj;
            try {
                obj = (JsonObject) opt;
            } catch (Throwable ignored) {
                continue;
            }
            // 小贾影视仓: 畸形/缺字段的站点直接跳过, 绝不让单站拖垮整份配置(部分线路如饭太硬手写配置偶尔缺字段)
            String siteKey, siteApi;
            int siteType;
            try {
                if (!obj.has("key") || obj.get("key").isJsonNull()) continue;
                siteKey = obj.get("key").getAsString().trim();
                if (siteKey.isEmpty()) continue;
                siteApi = obj.has("api") && !obj.get("api").isJsonNull() ? obj.get("api").getAsString().trim() : "";
                siteType = obj.has("type") && !obj.get("type").isJsonNull() ? obj.get("type").getAsInt() : 0;
            } catch (Throwable ignored) {
                continue;
            }
            SourceBean sb = new SourceBean();
            sb.setKey(siteKey);
            sb.setName(obj.has("name") && !obj.get("name").isJsonNull() ? obj.get("name").getAsString().trim() : siteKey);
            sb.setType(siteType);
            sb.setApi(siteApi);
            sb.setSearchable(DefaultConfig.safeJsonInt(obj, "searchable", 1));
            sb.setQuickSearch(DefaultConfig.safeJsonInt(obj, "quickSearch", 1));
            if(siteKey.startsWith("py_")){
                sb.setFilterable(1);
            }else {
                sb.setFilterable(DefaultConfig.safeJsonInt(obj, "filterable", 1));
            }
            sb.setHide(DefaultConfig.safeJsonInt(obj, "hide", 0));
            sb.setPlayerUrl(DefaultConfig.safeJsonString(obj, "playUrl", ""));
            sb.setExt(DefaultConfig.safeJsonString(obj, "ext", ""));
            sb.setJar(DefaultConfig.safeJsonString(obj, "jar", ""));
            sb.setPlayerType(DefaultConfig.safeJsonInt(obj, "playerType", -1));
            sb.setCategories(DefaultConfig.safeJsonStringList(obj, "categories"));
            sb.setClickSelector(DefaultConfig.safeJsonString(obj, "click", ""));
            sb.setStyle(DefaultConfig.safeJsonString(obj, "style", ""));
            if (firstSite == null && sb.getHide() == 0)
                firstSite = sb;
            // 小贾影视仓: 记录第一个非 meta 的内容站, 供首页兜底
            if (firstContentSite == null && sb.getHide() == 0 && !isMetaSite(sb))
                firstContentSite = sb;
            sourceBeanList.put(siteKey, sb);
        }
        if (sourceBeanList != null && sourceBeanList.size() > 0) {
            // 小贾影视仓: 切换线路时只清空"按站点key缓存的 spider 实例"(clearSpiderCache),
            // 保留已加载的 jar(classLoader)。这样既不会因复用旧线路同名站点的 stale spider 而搜索无结果,
            // 又不必重新下载/重dex jar —— 解决"切线路慢"与"首页加载不出来/推荐消失"。
            boolean lineChanged = !apiUrl.equals(lastApiUrl);
            lastApiUrl = apiUrl;
            if (lineChanged) {
                jarLoader.clearSpiderCache();
                // 小贾影视仓 v15: JS 爬虫实例也按 key 缓存, 切线路后同 key 的 JS 站会拿到旧线路爬虫,
                // 必须一并清掉, 否则该站首页/搜索仍是旧线路逻辑(表现为"切线路后搜索不显示")。
                try { jsLoader.clear(); } catch (Throwable ignored) { }
            }
            // 小贾影视仓: 首页推荐"锁死豆瓣"——优先线路自带豆瓣; 线路没有豆瓣则注入参考豆瓣(自带其全局jar, 自包含可用)
            String home = Hawk.get(HawkConfig.HOME_API, "");
            SourceBean sh = getSource(home);
            if (sh == null || sh.getHide() == 1 || !isDouban(sh)) {
                sh = null;
                // 1) 找当前线路自己的豆瓣站
                for (SourceBean sb : sourceBeanList.values()) {
                    if (isDouban(sb)) {
                        sh = sb;
                        break;
                    }
                }
                // 2) 找到则记作参考(含当前线路全局spider, 供以后注入)
                if (sh != null) {
                    saveReferenceDouban(sh);
                } else {
                    // 3) 线路没有豆瓣 → 注入参考豆瓣(带jar=参考线路全局spider, 不依赖本线路main jar)
                    if (referenceDouban != null) {
                        String refJar = referenceDoubanSpider;
                        // v15: 参考线路没有全局spider(如纯JSON线路)时, 退而用当前线路的spider; 都没有则注入站无法独立工作, 放弃注入
                        if (refJar == null || refJar.isEmpty()) refJar = spider == null ? "" : spider;
                        if (!refJar.isEmpty() && referenceDouban.getApi() != null && !referenceDouban.getApi().isEmpty()) {
                            SourceBean inj = new SourceBean();
                            inj.setKey("__xiaojia_douban");
                            inj.setName(referenceDouban.getName() + "·推荐");
                            inj.setType(referenceDouban.getType());
                            inj.setApi(referenceDouban.getApi());
                            inj.setExt(referenceDouban.getExt());
                            inj.setJar(refJar);
                            inj.setSearchable(0);
                            inj.setQuickSearch(0);
                            sourceBeanList.put(inj.getKey(), inj);
                            sh = inj;
                        }
                    }
                }
                // 4) 实在没有 → 找"可独立工作"的内容站兜底(jar非空 或 非type3, 不依赖可能缺失的main jar), 避免选到必挂的站
                if (sh == null) {
                    for (SourceBean sb : sourceBeanList.values()) {
                        if (sb.getHide() == 0 && !isMetaSite(sb)
                                && (sb.getJar() != null && !sb.getJar().isEmpty() || sb.getType() != 3)) {
                            sh = sb;
                            break;
                        }
                    }
                }
                if (sh == null) sh = firstContentSite != null ? firstContentSite : firstSite;
            }
            // v15: 兜底到的站若是 type3 且 jar 为空(依赖 main), 而本线路又没有全局 spider → 首页必然 SpiderNull 空白
            // 此时优先换一个可独立工作的站, 保证首页一定有内容
            if (sh != null && sh.getType() == 3 && (sh.getJar() == null || sh.getJar().isEmpty())
                    && (spider == null || spider.isEmpty())) {
                for (SourceBean sb : sourceBeanList.values()) {
                    if (sb.getHide() == 0 && !isMetaSite(sb) && sb != sh
                            && (sb.getJar() != null && !sb.getJar().isEmpty() || sb.getType() != 3)) {
                        sh = sb;
                        break;
                    }
                }
            }
            setSourceBean(sh);
        }
        // 需要使用vip解析的flag
        vipParseFlags = DefaultConfig.safeJsonStringList(infoJson, "flags");
        // 解析地址
        parseBeanList.clear();
        if (infoJson.has("parses")) {
            JsonArray parses = infoJson.get("parses").getAsJsonArray();
            for (JsonElement opt : parses) {
                JsonObject obj = (JsonObject) opt;
                ParseBean pb = new ParseBean();
                pb.setName(obj.get("name").getAsString().trim());
                pb.setUrl(obj.get("url").getAsString().trim());
                String ext = obj.has("ext") ? obj.get("ext").getAsJsonObject().toString() : "";
                pb.setExt(ext);
                pb.setType(DefaultConfig.safeJsonInt(obj, "type", 0));
                parseBeanList.add(pb);
            }
            if(!parseBeanList.isEmpty()){
                addSuperParse();
            }
        }
        // 获取默认解析
        if (parseBeanList != null && parseBeanList.size() > 0) {
            String defaultParse = Hawk.get(HawkConfig.DEFAULT_PARSE, "");
            if (!TextUtils.isEmpty(defaultParse))
                for (ParseBean pb : parseBeanList) {
                    if (pb.getName().equals(defaultParse))
                        setDefaultParse(pb);
                }
            if (mDefaultParse == null)
                setDefaultParse(parseBeanList.get(0));
        }

        // takagen99: Check if Live URL is setup in Settings, if no, get from File Config
        liveChannelGroupList.clear();           //修复从后台切换重复加载频道列表
        String liveURL = Hawk.get(HawkConfig.LIVE_URL, "https://a9828bdfc5df47239936c04f6cd73104.app.workbuddy.link/live.m3u");
        String epgURL  = Hawk.get(HawkConfig.EPG_URL, "");

        String liveURL_final = null;
        try {
            if (infoJson.has("lives") && infoJson.get("lives").getAsJsonArray() != null) {
                JsonObject livesOBJ = infoJson.get("lives").getAsJsonArray().get(0).getAsJsonObject();
                String lives = livesOBJ.toString();
                int index = lives.indexOf("proxy://");
                if (index != -1) {
                    int endIndex = lives.lastIndexOf("\"");
                    String url = lives.substring(index, endIndex);
                    url = DefaultConfig.checkReplaceProxy(url);

                    //clan
                    String extUrl = Uri.parse(url).getQueryParameter("ext");
                    if (extUrl != null && !extUrl.isEmpty()) {
                        String extUrlFix;
                        if (extUrl.startsWith("http") || extUrl.startsWith("clan://")) {
                            extUrlFix = extUrl;
                        } else {
                            extUrlFix = new String(Base64.decode(extUrl, Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP), "UTF-8");
                        }
                        if (extUrlFix.startsWith("clan://")) {
                            extUrlFix = clanContentFix(clanToAddress(apiUrl), extUrlFix);
                        }

                        // takagen99: Capture Live URL into Config
                        System.out.println("Live URL :" + extUrlFix);
                        putLiveHistory(extUrlFix);
                        // Overwrite with Live URL from Settings
                        if (StringUtils.isBlank(liveURL)) {
                            Hawk.put(HawkConfig.LIVE_URL, extUrlFix);
                        } else {
                            extUrlFix = liveURL;
                        }

                        // Final Live URL
                        liveURL_final = extUrlFix;

//                    // Encoding the Live URL
//                    extUrlFix = Base64.encodeToString(extUrlFix.getBytes("UTF-8"), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP);
//                    url = url.replace(extUrl, extUrlFix);
                    }

                    // takagen99 : Getting EPG URL from File Config & put into Settings
                    if (livesOBJ.has("epg")) {
                        String epg = livesOBJ.get("epg").getAsString();
                        System.out.println("EPG URL :" + epg);
                        putEPGHistory(epg);
                        // Overwrite with EPG URL from Settings
                        if (StringUtils.isBlank(epgURL)) {
                            Hawk.put(HawkConfig.EPG_URL, epg);
                        } else {
                            Hawk.put(HawkConfig.EPG_URL, epgURL);
                        }
                    }

//                // Populate Live Channel Listing
//                LiveChannelGroup liveChannelGroup = new LiveChannelGroup();
//                liveChannelGroup.setGroupName(url);
//                liveChannelGroupList.add(liveChannelGroup);

                } else {

                    // if FongMi Live URL Formatting exists
                    if (!lives.contains("type")) {
                        loadLives(infoJson.get("lives").getAsJsonArray());
                    } else {
                        JsonObject fengMiLives = infoJson.get("lives").getAsJsonArray().get(0).getAsJsonObject();
                        Hawk.put(HawkConfig.LIVE_PLAYER_TYPE, DefaultConfig.safeJsonInt(fengMiLives, "playerType", -1));
                        String type = fengMiLives.get("type").getAsString();
                        if (type.equals("0")) {
                            String url = fengMiLives.get("url").getAsString();

                            // takagen99 : Getting EPG URL from File Config & put into Settings
                            if (fengMiLives.has("epg")) {
                                String epg = fengMiLives.get("epg").getAsString();
                                System.out.println("EPG URL :" + epg);
                                putEPGHistory(epg);
                                // Overwrite with EPG URL from Settings
                                if (StringUtils.isBlank(epgURL)) {
                                    Hawk.put(HawkConfig.EPG_URL, epg);
                                } else {
                                    Hawk.put(HawkConfig.EPG_URL, epgURL);
                                }
                            }

                            if (url.startsWith("http")) {
                                // takagen99: Capture Live URL into Settings
                                System.out.println("Live URL :" + url);
                                putLiveHistory(url);
                                // Overwrite with Live URL from Settings
                                if (StringUtils.isBlank(liveURL)) {
                                    Hawk.put(HawkConfig.LIVE_URL, url);
                                } else {
                                    url = liveURL;
                                }

                                // Final Live URL
                                liveURL_final = url;

//                            url = Base64.encodeToString(url.getBytes("UTF-8"), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP);
                            }
                        }
                    }
                }

                // takagen99: Load Live Channel from settings URL (WIP)
                if (StringUtils.isBlank(liveURL_final)) {
                    liveURL_final = liveURL;
                }
                liveURL_final = Base64.encodeToString(liveURL_final.getBytes("UTF-8"), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP);
                liveURL_final = "http://127.0.0.1:9978/proxy?do=live&type=txt&ext=" + liveURL_final;
                LiveChannelGroup liveChannelGroup = new LiveChannelGroup();
                liveChannelGroup.setGroupName(liveURL_final);
                liveChannelGroupList.add(liveChannelGroup);
            }


        } catch (Throwable th) {
            th.printStackTrace();
        }

        // Video parse rule for host
        if (infoJson.has("rules")) {
            VideoParseRuler.clearRule();
            for(JsonElement oneHostRule : infoJson.getAsJsonArray("rules")) {
                JsonObject obj = (JsonObject) oneHostRule;
                //嗅探过滤规则
                if (obj.has("host")) {
                    String host = obj.get("host").getAsString();
                    if (obj.has("rule")) {
                        JsonArray ruleJsonArr = obj.getAsJsonArray("rule");
                        ArrayList<String> rule = new ArrayList<>();
                        for (JsonElement one : ruleJsonArr) {
                            String oneRule = one.getAsString();
                            rule.add(oneRule);
                        }
                        if (rule.size() > 0) {
                            VideoParseRuler.addHostRule(host, rule);
                        }
                    }
                    if (obj.has("filter")) {
                        JsonArray filterJsonArr = obj.getAsJsonArray("filter");
                        ArrayList<String> filter = new ArrayList<>();
                        for (JsonElement one : filterJsonArr) {
                            String oneFilter = one.getAsString();
                            filter.add(oneFilter);
                        }
                        if (filter.size() > 0) {
                            VideoParseRuler.addHostFilter(host, filter);
                        }
                    }
                }
                //广告过滤规则
                if (obj.has("hosts") && obj.has("regex")) {
                    ArrayList<String> rule = new ArrayList<>();
                    ArrayList<String> ads = new ArrayList<>();
                    JsonArray regexArray = obj.getAsJsonArray("regex");
                    for (JsonElement one : regexArray) {
                        String regex = one.getAsString();
                        if (M3U8.isAd(regex)) ads.add(regex);
                        else rule.add(regex);
                    }
                    JsonArray array = obj.getAsJsonArray("hosts");
                    for (JsonElement one : array) {
                        String host = one.getAsString();
                        VideoParseRuler.addHostRule(host, rule);
                        VideoParseRuler.addHostRegex(host, ads);
                    }
                }
                //嗅探脚本规则 如 click
                if (obj.has("hosts") && obj.has("script")) {
                    ArrayList<String> scripts = new ArrayList<>();
                    JsonArray scriptArray = obj.getAsJsonArray("script");
                    for (JsonElement one : scriptArray) {
                        String script = one.getAsString();
                        scripts.add(script);
                    }
                    JsonArray array = obj.getAsJsonArray("hosts");
                    for (JsonElement one : array) {
                        String host = one.getAsString();
                        VideoParseRuler.addHostScript(host, scripts);
                    }
                }
            }
        }

        String defaultIJKADS = "{\"ijk\":[{\"options\":[{\"name\":\"opensles\",\"category\":4,\"value\":\"0\"},{\"name\":\"overlay-format\",\"category\":4,\"value\":\"842225234\"},{\"name\":\"framedrop\",\"category\":4,\"value\":\"0\"},{\"name\":\"soundtouch\",\"category\":4,\"value\":\"1\"},{\"name\":\"start-on-prepared\",\"category\":4,\"value\":\"1\"},{\"name\":\"http-detect-rangeupport\",\"category\":1,\"value\":\"0\"},{\"name\":\"fflags\",\"category\":1,\"value\":\"fastseek\"},{\"name\":\"skip_loop_filter\",\"category\":2,\"value\":\"48\"},{\"name\":\"reconnect\",\"category\":4,\"value\":\"1\"},{\"name\":\"enable-accurate-seek\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-auto-rotate\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-handle-resolution-change\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-hevc\",\"category\":4,\"value\":\"0\"},{\"name\":\"dns_cache_timeout\",\"category\":1,\"value\":\"600000000\"}],\"group\":\"软解码\"},{\"options\":[{\"name\":\"opensles\",\"category\":4,\"value\":\"0\"},{\"name\":\"overlay-format\",\"category\":4,\"value\":\"842225234\"},{\"name\":\"framedrop\",\"category\":4,\"value\":\"0\"},{\"name\":\"soundtouch\",\"category\":4,\"value\":\"1\"},{\"name\":\"start-on-prepared\",\"category\":4,\"value\":\"1\"},{\"name\":\"http-detect-rangeupport\",\"category\":1,\"value\":\"0\"},{\"name\":\"fflags\",\"category\":1,\"value\":\"fastseek\"},{\"name\":\"skip_loop_filter\",\"category\":2,\"value\":\"48\"},{\"name\":\"reconnect\",\"category\":4,\"value\":\"1\"},{\"name\":\"enable-accurate-seek\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-auto-rotate\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-handle-resolution-change\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-hevc\",\"category\":4,\"value\":\"1\"},{\"name\":\"dns_cache_timeout\",\"category\":1,\"value\":\"600000000\"}],\"group\":\"硬解码\"}],\"ads\":[\"mimg.0c1q0l.cn\",\"www.googletagmanager.com\",\"www.google-analytics.com\",\"mc.usihnbcq.cn\",\"mg.g1mm3d.cn\",\"mscs.svaeuzh.cn\",\"cnzz.hhttm.top\",\"tp.vinuxhome.com\",\"cnzz.mmstat.com\",\"www.baihuillq.com\",\"s23.cnzz.com\",\"z3.cnzz.com\",\"c.cnzz.com\",\"stj.v1vo.top\",\"z12.cnzz.com\",\"img.mosflower.cn\",\"tips.gamevvip.com\",\"ehwe.yhdtns.com\",\"xdn.cqqc3.com\",\"www.jixunkyy.cn\",\"sp.chemacid.cn\",\"hm.baidu.com\",\"s9.cnzz.com\",\"z6.cnzz.com\",\"um.cavuc.com\",\"mav.mavuz.com\",\"wofwk.aoidf3.com\",\"z5.cnzz.com\",\"xc.hubeijieshikj.cn\",\"tj.tianwenhu.com\",\"xg.gars57.cn\",\"k.jinxiuzhilv.com\",\"cdn.bootcss.com\",\"ppl.xunzhuo123.com\",\"xomk.jiangjunmh.top\",\"img.xunzhuo123.com\",\"z1.cnzz.com\",\"s13.cnzz.com\",\"xg.huataisangao.cn\",\"z7.cnzz.com\",\"xg.huataisangao.cn\",\"z2.cnzz.com\",\"s96.cnzz.com\",\"q11.cnzz.com\",\"thy.dacedsfa.cn\",\"xg.whsbpw.cn\",\"s19.cnzz.com\",\"z8.cnzz.com\",\"s4.cnzz.com\",\"f5w.as12df.top\",\"ae01.alicdn.com\",\"www.92424.cn\",\"k.wudejia.com\",\"vivovip.mmszxc.top\",\"qiu.xixiqiu.com\",\"cdnjs.hnfenxun.com\",\"cms.qdwght.com\"]}";
        JsonObject defaultJson = new Gson().fromJson(defaultIJKADS, JsonObject.class);
        // 广告地址
        if(AdBlocker.isEmpty()){
            //默认广告拦截
            for (JsonElement host : defaultJson.getAsJsonArray("ads")) {
                AdBlocker.addAdHost(host.getAsString());
            }
            //追加的广告拦截
            if(infoJson.has("ads")){
                for (JsonElement host : infoJson.getAsJsonArray("ads")) {
                    if(!AdBlocker.hasHost(host.getAsString())){
                        AdBlocker.addAdHost(host.getAsString());
                    }
                }
            }
        }
        // IJK解码配置
        if (ijkCodes == null) {
            ijkCodes = new ArrayList<>();
            boolean foundOldSelect = false;
            String ijkCodec = Hawk.get(HawkConfig.IJK_CODEC, "");
            JsonArray ijkJsonArray = infoJson.has("ijk") ? infoJson.get("ijk").getAsJsonArray() : defaultJson.get("ijk").getAsJsonArray();
            for (JsonElement opt : ijkJsonArray) {
                JsonObject obj = (JsonObject) opt;
                String name = obj.get("group").getAsString();
                LinkedHashMap<String, String> baseOpt = new LinkedHashMap<>();
                for (JsonElement cfg : obj.get("options").getAsJsonArray()) {
                    JsonObject cObj = (JsonObject) cfg;
                    String key = cObj.get("category").getAsString() + "|" + cObj.get("name").getAsString();
                    String val = cObj.get("value").getAsString();
                    baseOpt.put(key, val);
                }
                IJKCode codec = new IJKCode();
                codec.setName(name);
                codec.setOption(baseOpt);
                if (name.equals(ijkCodec) || TextUtils.isEmpty(ijkCodec)) {
                    codec.selected(true);
                    ijkCodec = name;
                    foundOldSelect = true;
                } else {
                    codec.selected(false);
                }
                ijkCodes.add(codec);
            }
            if (!foundOldSelect && ijkCodes.size() > 0) {
                ijkCodes.get(0).selected(true);
            }
        }
    }

    // 小贾影视仓: 安全地去掉 JSON 里的 // 行注释 与 /* */ 块注释, 但保留字符串内的 // (如 http://)
    private static String stripJsonComments(String json) {
        if (json == null) return null;
        StringBuilder out = new StringBuilder();
        boolean inString = false;
        boolean inLine = false;
        boolean inBlock = false;
        char prev = 0;
        int n = json.length();
        for (int i = 0; i < n; i++) {
            char c = json.charAt(i);
            if (inLine) {
                if (c == '\n') { inLine = false; out.append(c); }
                prev = c;
                continue;
            }
            if (inBlock) {
                if (c == '/' && prev == '*') inBlock = false;
                prev = c;
                continue;
            }
            if (c == '"' && prev != '\\') {
                inString = !inString;
                out.append(c);
                prev = c;
                continue;
            }
            if (!inString) {
                if (c == '/' && i + 1 < n && json.charAt(i + 1) == '/') { inLine = true; prev = c; continue; }
                if (c == '/' && i + 1 < n && json.charAt(i + 1) == '*') { inBlock = true; prev = c; continue; }
            }
            out.append(c);
            prev = c;
        }
        return out.toString();
    }

    // 小贾影视仓: 判断响应字节是否为图片配置(图片二进制后面藏了 base64 编码的 JSON 配置)
    private static boolean isImageConfig(byte[] raw) {
        if (raw == null || raw.length < 8) return false;
        // PNG
        if (raw[0] == (byte) 0x89 && raw[1] == 0x50 && raw[2] == 0x4E && raw[3] == 0x47) return true;
        // JPEG
        if (raw[0] == (byte) 0xFF && raw[1] == (byte) 0xD8 && raw[2] == (byte) 0xFF) return true;
        // GIF
        if (raw[0] == 0x47 && raw[1] == 0x49 && raw[2] == 0x46) return true;
        // BMP
        if (raw[0] == 0x42 && raw[1] == 0x4D) return true;
        // WEBP (RIFF....WEBP)
        if (raw[0] == 0x52 && raw[1] == 0x49 && raw[2] == 0x46 && raw[3] == 0x46
                && raw[4] == 0x57 && raw[5] == 0x45 && raw[6] == 0x42 && raw[7] == 0x50) return true;
        return false;
    }

    // 小贾影视仓: 从图片字节里提取藏在后面的 base64 配置(取最长 base64 串并尝试解码为合法 JSON)
    private static String extractImageConfig(byte[] raw) {
        try {
            // 图片二进制用 Latin1 映射, base64 字符(ASCII)1:1 保留, 二进制高位字节不会误命中
            String s = new String(raw, "ISO-8859-1");
            Pattern p = Pattern.compile("[A-Za-z0-9+/]{40,}={0,2}");
            Matcher m = p.matcher(s);
            List<String> runs = new ArrayList<>();
            while (m.find()) runs.add(m.group());
            // 长度降序, 优先尝试最长的一段(图片本体为压缩数据, 几乎不会出现这么长的连续 base64)
            runs.sort((a, b) -> Integer.compare(b.length(), a.length()));
            for (String cand : runs) {
                try {
                    byte[] dec = Base64.decode(cand, Base64.DEFAULT);
                    String json = new String(dec, "UTF-8");
                    String t = json.trim();
                    if (t.startsWith("{") && (t.contains("\"sites\"") || t.contains("\"spider\"") || t.contains("\"video\""))) {
                        return json;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // 小贾影视仓: 判断是否为 meta 公告/配置/网盘等非内容站(避免被当首页导致"推荐消失")
    private static boolean isMetaSite(SourceBean sb) {
        if (sb == null) return true;
        String n = sb.getName();
        if (n == null || n.isEmpty()) return true;
        String[] metaKw = {"配置中心", "配置┃", "设置", "网盘", "盘搜", "公告", "指南", "扫码", "导航", "我的网盘", "更新日期", "声明"};
        for (String k : metaKw) if (n.contains(k)) return true;
        // 牛二/王二小系列公告站 "🐮【...】" "⬇️【...】"
        if (n.startsWith("🐮【") || n.startsWith("⬇️【")) return true;
        return false;
    }

    // 小贾影视仓: 判断站点是否为"豆瓣"类(名字含豆瓣 或 key 含 douban)
    private static boolean isDouban(SourceBean sb) {
        if (sb == null) return false;
        String n = sb.getName();
        String k = sb.getKey();
        if (n != null && n.contains("豆瓣")) return true;
        if (k != null && k.toLowerCase().contains("douban")) return true;
        return false;
    }

    // 小贾影视仓: 从 Hawk 恢复参考豆瓣(首页"锁死豆瓣"用)
    private void loadReferenceDouban() {
        try {
            String json = Hawk.get(HAWK_REF_DOUBAN, "");
            if (json != null && !json.isEmpty()) {
                JsonObject o = new Gson().fromJson(json, JsonObject.class);
                referenceDouban = new Gson().fromJson(o.getAsJsonObject("site"), SourceBean.class);
                referenceDoubanSpider = o.has("spider") ? o.get("spider").getAsString() : "";
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    // 小贾影视仓: 记住参考豆瓣 + 它所在线路的全局 spider(注入时作为该豆瓣的jar, 自包含可用)
    // v15.1: 只在"还没有参考"时记录 —— 避免参考被后续大 jar 线路(肥猫 900KB/饭太硬 1.1MB)反复覆盖,
    // 导致跨线路注入时每次都要重新下载一个巨大的参考 jar(首页因此多等数秒)。首个含豆瓣的线路通常
    // 就是轻量的 itv666(22KB jar), 锁定它即可让注入站始终复用同一份已缓存 jar。
    private void saveReferenceDouban(SourceBean site) {
        if (site == null) return;
        if (referenceDouban != null) return;
        referenceDouban = site;
        referenceDoubanSpider = spider == null ? "" : spider;
        try {
            JsonObject o = new JsonObject();
            o.add("site", new Gson().toJsonTree(site));
            o.addProperty("spider", referenceDoubanSpider);
            Hawk.put(HAWK_REF_DOUBAN, o.toString());
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private void putLiveHistory(String url) {
        if (!url.isEmpty()) {
            ArrayList<String> liveHistory = Hawk.get(HawkConfig.LIVE_HISTORY, new ArrayList<String>());
            if (!liveHistory.contains(url))
                liveHistory.add(0, url);
            if (liveHistory.size() > 20)
                liveHistory.remove(20);
            Hawk.put(HawkConfig.LIVE_HISTORY, liveHistory);
        }
    }

    public static void putEPGHistory(String url) {
        if (!url.isEmpty()) {
            ArrayList<String> epgHistory = Hawk.get(HawkConfig.EPG_HISTORY, new ArrayList<String>());
            if (!epgHistory.contains(url))
                epgHistory.add(0, url);
            if (epgHistory.size() > 20)
                epgHistory.remove(20);
            Hawk.put(HawkConfig.EPG_HISTORY, epgHistory);
        }
    }

    public void loadLives(JsonArray livesArray) {
        liveChannelGroupList.clear();
        int groupIndex = 0;
        int channelIndex = 0;
        int channelNum = 0;
        for (JsonElement groupElement : livesArray) {
            LiveChannelGroup liveChannelGroup = new LiveChannelGroup();
            liveChannelGroup.setLiveChannels(new ArrayList<LiveChannelItem>());
            liveChannelGroup.setGroupIndex(groupIndex++);
            String groupName = ((JsonObject) groupElement).get("group").getAsString().trim();
            String[] splitGroupName = groupName.split("_", 2);
            liveChannelGroup.setGroupName(splitGroupName[0]);
            if (splitGroupName.length > 1)
                liveChannelGroup.setGroupPassword(splitGroupName[1]);
            else
                liveChannelGroup.setGroupPassword("");
            channelIndex = 0;
            for (JsonElement channelElement : ((JsonObject) groupElement).get("channels").getAsJsonArray()) {
                JsonObject obj = (JsonObject) channelElement;
                LiveChannelItem liveChannelItem = new LiveChannelItem();
                liveChannelItem.setChannelName(obj.get("name").getAsString().trim());
                liveChannelItem.setChannelIndex(channelIndex++);
                liveChannelItem.setChannelNum(++channelNum);
                ArrayList<String> urls = DefaultConfig.safeJsonStringList(obj, "urls");
                ArrayList<String> sourceNames = new ArrayList<>();
                ArrayList<String> sourceUrls = new ArrayList<>();
                int sourceIndex = 1;
                for (String url : urls) {
                    String[] splitText = url.split("\\$", 2);
                    sourceUrls.add(splitText[0]);
                    if (splitText.length > 1)
                        sourceNames.add(splitText[1]);
                    else
                        sourceNames.add("源" + sourceIndex);
                    sourceIndex++;
                }
                liveChannelItem.setChannelSourceNames(sourceNames);
                liveChannelItem.setChannelUrls(sourceUrls);
                liveChannelGroup.getLiveChannels().add(liveChannelItem);
            }
            liveChannelGroupList.add(liveChannelGroup);
        }
    }

    public String getSpider() {
        return spider;
    }

    public Spider getCSP(SourceBean sourceBean) {
        if (sourceBean.getApi().endsWith(".js") || sourceBean.getApi().contains(".js?")){
            return jsLoader.getSpider(sourceBean.getKey(), sourceBean.getApi(), sourceBean.getExt(), sourceBean.getJar());
        }else if (sourceBean.getApi().contains(".py")) {
            return pyLoader.getSpider(sourceBean.getKey(), sourceBean.getApi(), sourceBean.getExt());
        } else {
            return jarLoader.getSpider(sourceBean.getKey(), sourceBean.getApi(), sourceBean.getExt(), sourceBean.getJar());
        }
    }

    public Spider getPyCSP(String url) {
        return pyLoader.getSpider(MD5.string2MD5(url), url, "");
    }
	
    public Object[] proxyLocal(Map<String,String> param) {
        if ("js".equals(param.get("do"))) {
            return jsLoader.proxyInvoke(param);
        }
        SourceBean sourceBean = ApiConfig.get().getHomeSourceBean(); 
        String apiString = sourceBean.getApi();
        return apiString.contains(".py") ? pyLoader.proxyInvoke(param) : jarLoader.proxyInvoke(param);
    }

    public JSONObject jsonExt(String key, LinkedHashMap<String, String> jxs, String url) {
        return jarLoader.jsonExt(key, jxs, url);
    }

    public JSONObject jsonExtMix(String flag, String key, String name, LinkedHashMap<String, HashMap<String, String>> jxs, String url) {
        return jarLoader.jsonExtMix(flag, key, name, jxs, url);
    }

    public interface LoadConfigCallback {
        void success();

        void retry();

        void error(String msg);
    }

    public interface FastParseCallback {
        void success(boolean parse, String url, Map<String, String> header);

        void fail(int code, String msg);
    }

    public SourceBean getSource(String key) {
        if (!sourceBeanList.containsKey(key))
            return null;
        return sourceBeanList.get(key);
    }

    public void setSourceBean(SourceBean sourceBean) {
        this.mHomeSource = sourceBean;
        Hawk.put(HawkConfig.HOME_API, sourceBean.getKey());
    }

    public void setDefaultParse(ParseBean parseBean) {
        if (this.mDefaultParse != null)
            this.mDefaultParse.setDefault(false);
        this.mDefaultParse = parseBean;
        Hawk.put(HawkConfig.DEFAULT_PARSE, parseBean.getName());
        parseBean.setDefault(true);
    }

    public ParseBean getDefaultParse() {
        return mDefaultParse;
    }

    public List<SourceBean> getSourceBeanList() {
        return new ArrayList<>(sourceBeanList.values());
    }

    public List<ParseBean> getParseBeanList() {
        return parseBeanList;
    }

    public List<String> getVipParseFlags() {
        return vipParseFlags;
    }

    public SourceBean getHomeSourceBean() {
        return mHomeSource == null ? emptyHome : mHomeSource;
    }

    public List<LiveChannelGroup> getChannelGroupList() {
        return liveChannelGroupList;
    }

    public List<IJKCode> getIjkCodes() {
        return ijkCodes;
    }

    public IJKCode getCurrentIJKCode() {
        String codeName = Hawk.get(HawkConfig.IJK_CODEC, "");
        return getIJKCodec(codeName);
    }

    public IJKCode getIJKCodec(String name) {
        for (IJKCode code : ijkCodes) {
            if (code.getName().equals(name))
                return code;
        }
        return ijkCodes.get(0);
    }

    public JsonArray getLivePlayHeaders() {
        return livePlayHeaders;
    }

    String clanToAddress(String lanLink) {
        if (lanLink.startsWith("clan://localhost/")) {
            return lanLink.replace("clan://localhost/", ControlManager.get().getAddress(true) + "file/");
        } else {
            String link = lanLink.substring(7);
            int end = link.indexOf('/');
            return "http://" + link.substring(0, end) + "/file/" + link.substring(end + 1);
        }
    }

    String clanContentFix(String lanLink, String content) {
        String fix = lanLink.substring(0, lanLink.indexOf("/file/") + 6);
        return content.replace("clan://", fix);
    }

    String fixContentPath(String url, String content) {
        if (content.contains("\"./")) {
            url=url.replace("file://","clan://localhost/");
            if (!url.startsWith("http") && !url.startsWith("clan://")) {
                url = "http://" + url;
            }
            if (url.startsWith("clan://")) url = clanToAddress(url);
            content = content.replace("./", url.substring(0, url.lastIndexOf("/") + 1));
        }
        return content;
    }

    public void clearJarLoader()
    {
        jarLoader.clear();
    }
    private void addSuperParse(){
        ParseBean superPb = new ParseBean();
        superPb.setName("超级解析");
        superPb.setUrl("SuperParse");
        superPb.setExt("");
        superPb.setType(4);
        parseBeanList.add(0, superPb);
    }
    public void clearLoader(){
        jarLoader.clear();
        pyLoader.clear();
        jsLoader.clear();
    }
    String miTV(String url) {
        if (url.startsWith("p") || url.startsWith("mitv")) {

        }
        return url;
    }

}
