package com.github.tvbox.osc.base;

import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import androidx.core.os.HandlerCompat;
import androidx.multidex.MultiDexApplication;

import com.github.catvod.crawler.JarLoader;
import com.github.catvod.crawler.JsLoader;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.callback.EmptyCallback;
import com.github.tvbox.osc.callback.LoadingCallback;
import com.github.tvbox.osc.data.AppDataManager;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.util.EpgUtil;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LocaleHelper;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.OkGoHelper;
import com.github.tvbox.osc.util.PlayerAuto;
import com.github.tvbox.osc.util.PlayerHelper;
import com.github.tvbox.osc.util.SubtitleHelper;
import com.hjq.permissions.XXPermissions;
import com.kingja.loadsir.core.LoadSir;
import com.orhanobut.hawk.Hawk;
import com.p2p.P2PClass;
import com.whl.quickjs.android.QuickJSLoader;
import com.yanzhenjie.andserver.AndServer;
import com.yanzhenjie.andserver.Server;

import java.io.File;
import java.util.concurrent.TimeUnit;

import io.github.inflationx.calligraphy3.CalligraphyConfig;
import io.github.inflationx.calligraphy3.CalligraphyInterceptor;
import io.github.inflationx.viewpump.ViewPump;
import me.jessyan.autosize.AutoSizeConfig;
import me.jessyan.autosize.unit.Subunits;

/**
 * @author pj567
 * @date :2020/12/17
 * @description:
 */
public class App extends MultiDexApplication {
    private static App instance;
    private static P2PClass p;
    public static String burl;
    private static String dashData;
    public static ViewPump viewPump = null;
    private static Server server = null;
    private final Handler handler;

    public App() {
        instance = this;
        handler = HandlerCompat.createAsync(Looper.getMainLooper());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // 小贾影视仓 v27.3: music 包全局上下文(MusicPlayer 单例用)
        com.github.tvbox.osc.music.AppContextHolder.init(this);
        // 小贾影视仓 v15.1: 全局崩溃日志 —— 任何未捕获异常(含切线路闪退)追加写入私有目录 xj_crash.log,
        // 便于线上诊断。捕获后仍交给系统默认处理器(照常弹崩溃/重启)。
        try {
            final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread thread, Throwable ex) {
                    try {
                        java.io.StringWriter sw = new java.io.StringWriter();
                        ex.printStackTrace(new java.io.PrintWriter(sw));
                        String log = "== " + new java.util.Date() + " [" + thread.getName() + "] ==\n" + sw + "\n\n";
                        java.io.File f = new java.io.File(getFilesDir(), "xj_crash.log");
                        java.io.FileOutputStream fos = new java.io.FileOutputStream(f, true);
                        fos.write(log.getBytes("UTF-8"));
                        fos.close();
                        // v15.4.3: 另存一份"最后一次崩溃"(覆盖写), 供下次启动弹窗一键复制上报
                        java.io.File f2 = new java.io.File(getFilesDir(), "xj_crash_last.txt");
                        java.io.FileOutputStream fos2 = new java.io.FileOutputStream(f2, false);
                        fos2.write(log.getBytes("UTF-8"));
                        fos2.close();
                    } catch (Throwable ignored) {
                    }
                    if (def != null) {
                        def.uncaughtException(thread, ex);
                    } else {
                        android.os.Process.killProcess(android.os.Process.myPid());
                    }
                }
            });
        } catch (Throwable ignored) {
        }
        // 小贾影视仓 v19.1: 落一份「本机内存页大小」到私有目录 —— 加固 .so 能否 dlopen 与页大小强相关,
        // 4KB 页设备上用原始 4KB 对齐 .so 即可; 16KB 页设备才需要 16KB 对齐(且不能改字节)。
        // 有此文件后, /crash 诊断通道可直接看到设备页大小, 不必再靠推断。
        try {
            long pageSize = android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE);
            java.io.File pf = new java.io.File(getFilesDir(), "xj_pagesize.txt");
            java.io.FileOutputStream pfo = new java.io.FileOutputStream(pf, false);
            pfo.write(("PAGE_SIZE=" + pageSize + "  android=" + android.os.Build.VERSION.RELEASE
                    + "  abi=" + android.os.Build.SUPPORTED_ABIS[0]
                    + "  model=" + android.os.Build.MODEL
                    + "  @ " + new java.util.Date() + "\n").getBytes("UTF-8"));
            pfo.close();
        } catch (Throwable ignored) {
        }
        // 小贾影视仓 v15.15: 启动期"防崩溃"加固 —— onCreate 里任何一步抛异常都会导致"点图标即闪退",
        // 逐项 try/catch 隔离: 单项失败只降级该项功能, 绝不让整个 App 起不来。
        try {
            SubtitleHelper.initSubtitleColor(this);
        } catch (Throwable ignored) {
        }
        try {
            initParams();
        } catch (Throwable ignored) {
        }
        // takagen99 : Initialize Locale
        try {
            initLocale();
        } catch (Throwable ignored) {
        }
        // OKGo
        try {
            OkGoHelper.init();
        } catch (Throwable ignored) {
        }
        // 闭关检查模式
        try {
            XXPermissions.setCheckMode(false);
        } catch (Throwable ignored) {
        }
        // Get EPG Info
        try {
            EpgUtil.init();
        } catch (Throwable ignored) {
        }
        // 初始化Web服务器
        try {
            ControlManager.init(this);
        } catch (Throwable ignored) {
        }
        //初始化数据库
        try {
            AppDataManager.init();
        } catch (Throwable ignored) {
        }
        LoadSir.beginBuilder()
                .addCallback(new EmptyCallback())
                .addCallback(new LoadingCallback())
                .commit();
        try {
            AutoSizeConfig.getInstance().setCustomFragment(true).getUnitsManager()
                    .setSupportDP(false)
                    .setSupportSP(false)
                    .setSupportSubunits(Subunits.MM);
            // 小贾影视仓 v23: App 已由「锁横屏」改为「锁竖屏」——
            // AutoSize 按 screenWidth/designWidthInDp(=1280) 算缩放系数，所以必须让 screenWidth = 屏幕**短边**:
            //   竖屏下短边 = 实际屏宽(如 Mate70 的 1224) -> 系数 0.956, 元素占屏宽比例与横屏一致;
            //   若错用长边(2700) -> 系数 2.11, 全站元素放大 2.2 倍, 竖屏 UI 直接撑爆。
            // Android 14+ 冷启动时 ScreenUtils 可能拿到未旋转的尺寸，所以这里无条件把大的那个换到 screenHeight。
            int screenWidth = AutoSizeConfig.getInstance().getScreenWidth();
            int screenHeight = AutoSizeConfig.getInstance().getScreenHeight();
            if (screenWidth > screenHeight) {
                AutoSizeConfig.getInstance().setScreenWidth(screenHeight);
                AutoSizeConfig.getInstance().setScreenHeight(screenWidth);
            }
        } catch (Throwable ignored) {
        }
        try {
            PlayerHelper.init();
        } catch (Throwable ignored) {
        }

        // Delete Cache
        /*File dir = getCacheDir();
        FileUtils.recursiveDelete(dir);
        dir = getExternalCacheDir();
        FileUtils.recursiveDelete(dir);*/

        try {
            FileUtils.cleanPlayerCache();
        } catch (Throwable ignored) {
        }

        // Add JS support
        try {
            QuickJSLoader.init();
        } catch (Throwable ignored) {
        }

        // 小贾影视仓 v24.1: Python 运行时提前预热 —— 原来挂在首次 ApiConfig.get()(首个 Activity 创建)才启动,
        // 冷启动白白浪费几百毫秒; 提前到 Application.onCreate 后台线程启动, 首次进 py 源/切 py 线路更快。
        // startBoot 幂等(bootStarted/pyReady 双检), 与 pyLoader 构造器里的 setApplication 重复调用无副作用。
        // 注意: PythonLoader 只在 python 变体的 sourceSet 里(java 版没有), main 源集必须走反射, 不能直接 import。
        try {
            Object pyLoader = Class.forName("com.undcover.freedom.pyramid.PythonLoader")
                    .getMethod("getInstance").invoke(null);
            pyLoader.getClass().getMethod("setApplication", android.app.Application.class)
                    .invoke(pyLoader, this);
        } catch (Throwable ignored) {
        }

        // add font support, my tv embed font not include emoji
        String extStorageDir = Environment.getExternalStorageDirectory().getAbsolutePath();
        File fontFile = new File(extStorageDir + "/tvbox.ttf");
        if (fontFile.exists()) {
            viewPump = ViewPump.builder()
                    .addInterceptor(new CalligraphyInterceptor(
                            new CalligraphyConfig.Builder()
                                    .setDefaultFontPath(fontFile.getAbsolutePath())
                                    .setFontAttrId(R.attr.fontPath)
                                    .build()))
                    .build();
        }
    }

    public static P2PClass getp2p() {
        try {
            if (p == null) {
                p = new P2PClass(FileUtils.getExternalCachePath());
            }
            return p;
        } catch (Exception e) {
            LOG.e(e.toString());
            return null;
        }
    }


    private void initParams() {
        // Hawk
        Hawk.init(this).build();
        Hawk.put(HawkConfig.DEBUG_OPEN, false);

        // 首页选项
        putDefault(HawkConfig.HOME_SHOW_SOURCE, true);       //数据源显示: true=开启, false=关闭
        putDefault(HawkConfig.HOME_SEARCH_POSITION, false);  //按钮位置-搜索: true=上方, false=下方
        putDefault(HawkConfig.HOME_MENU_POSITION, true);     //按钮位置-设置: true=上方, false=下方
        putDefault(HawkConfig.HOME_REC, 1);                  //推荐: 0=豆瓣热播, 1=站点推荐, 2=观看历史
        // 小贾影视仓 v26.3: 用户明确要求"首页推荐改成站点推荐"。
        // putDefault 只在 key 不存在时生效 —— 旧包可能早已把 home_rec 持久化成 0(豆瓣)或
        // 2(历史), 老用户升级后仍是旧模式, 改默认值对他们无效。这里做一次性迁移:
        // 首次跑 v26.3 时强制切到 1(站点推荐), 之后用户仍可在设置里手动改, 迁移只跑一次。
        if (!Hawk.get("home_rec_migrated_v263", false)) {
            Hawk.put(HawkConfig.HOME_REC, 1);
            Hawk.put("home_rec_migrated_v263", true);
        }
        // v26.6: 默认线路换到实测最快最稳的一条(见 _env/_pick.py 的三轮实测)。
        // 老包 Hawk 里已经持久化了旧默认 http://itv666.cc/aowu/config.webp, 只改 strings.xml 不会生效,
        // 所以做一次性迁移; 但**只覆盖"还在用旧内置默认"的用户**, 用户自己选过的线路不动。
        try {
            if (!Hawk.get("api_default_migrated_v266", false)) {
                String cur = Hawk.get(HawkConfig.API_URL, "");
                if (cur == null || cur.isEmpty() || cur.contains("itv666.cc/aowu")) {
                    Hawk.put(HawkConfig.API_URL, getString(R.string.app_source));
                }
                Hawk.put("api_default_migrated_v266", true);
            }
        } catch (Throwable ignored) {
        }
        // v27.24: 代理域名迁移。v6.gh-proxy.org / ghproxy.monkeyray.net / ghfast.top 这三个 GitHub 反代
        // 现在都拉不动了(实测 25~60 秒超时, 3/3 失败), 而默认线路正是走 v6.gh-proxy.org —— 冷启动一直等到
        // 超时才走兜底, 用户感知就是"打开特别慢/转圈"。统一换到 445569.pages.dev(0.7~1.3 秒, 3/3 成功)。
        // 只换域名、保留原路径, 所以用户自己选过的线路(南风 XC 等)也会被一并救回来。
        // 顺带: hgyx 的配置已把站点整段 // 注释掉, 解析必失败, 选中它的用户回落到默认线路。
        try {
            if (!Hawk.get("api_proxy_migrated_v2724", false)) {
                String cur = Hawk.get(HawkConfig.API_URL, "");
                if (cur != null && !cur.isEmpty()) {
                    // 换死掉的代理域名 + 中文域名转 punycode(okhttp 只认 ASCII 主机名)
                    String fixed = com.github.tvbox.osc.util.LineUrl.migrate(cur);
                    if (com.github.tvbox.osc.util.LineUrl.isDead(fixed)) fixed = getString(R.string.app_source);
                    if (!fixed.equals(cur)) Hawk.put(HawkConfig.API_URL, fixed);
                }
                Hawk.put("api_proxy_migrated_v2724", true);
            }
        } catch (Throwable ignored) {
        }
        // v27.27: 默认线路换成「挺好的·46站」。老包 Hawk 里存的是旧默认(摸鱼 102 站的 my.json)，
        // 只改 strings.xml 对已装用户不生效，所以做一次性迁移 —— 只搬"还在用旧内置默认"的人，
        // 用户自己选过的其它线路一律不动。
        try {
            if (!Hawk.get("api_default_migrated_v2727", false)) {
                String cur = Hawk.get(HawkConfig.API_URL, "");
                if (cur == null || cur.isEmpty() || cur.contains("backups/my.json")) {
                    Hawk.put(HawkConfig.API_URL, getString(R.string.app_source));
                    Hawk.put(HawkConfig.HOME_API, "");   // 首页站属于上一条线路，换线路后让它重新选
                }
                Hawk.put("api_default_migrated_v2727", true);
            }
        } catch (Throwable ignored) {
        }
        // v27.28: 默认线路换成「嗷呜·78站」。只把上一版默认(挺好的 thdjk.json)搬过来，
        // 用户自己选过的线路一律不动；换线路后清掉首页站记录，让新线路自己选第一个内容站。
        try {
            if (!Hawk.get("api_default_migrated_v2728", false)) {
                String cur = Hawk.get(HawkConfig.API_URL, "");
                if (cur != null && cur.contains("ztha.top/TVBox/thdjk.json")) {
                    Hawk.put(HawkConfig.API_URL, getString(R.string.app_source));
                    Hawk.put(HawkConfig.HOME_API, "");
                }
                Hawk.put("api_default_migrated_v2728", true);
            }
        } catch (Throwable ignored) {
        }
        putDefault(HawkConfig.HOME_NUM, 4);                  //历史条数: 0=20条, 1=40条, 2=60条, 3=80条, 4=100条
        // 播放器选项
        putDefault(HawkConfig.SHOW_PREVIEW, true);           //窗口预览: true=开启, false=关闭
        putDefault(HawkConfig.PLAY_SCALE, 0);                //画面缩放: 0=默认, 1=16:9, 2=4:3, 3=填充, 4=原始, 5=裁剪
        putDefault(HawkConfig.BACKGROUND_PLAY_TYPE, 0);      //后台：0=关闭, 1=开启, 2=画中画
        // v27.31: 内核默认「智能」—— 按站点+流类型实测结果在 IJK / Exo 之间自动选更快的。
        // 老用户迁移：只搬「还是旧默认 IJK」的人；手动选过 Exo/系统/外部播放器的一律不动。
        try {
            if (!Hawk.get("player_auto_migrated_v2731", false)) {
                Object cur = Hawk.get(HawkConfig.PLAY_TYPE, 1);
                if (cur instanceof Integer && (Integer) cur == 1) {
                    Hawk.put(HawkConfig.PLAY_TYPE, PlayerAuto.TYPE_AUTO);
                }
                Hawk.put("player_auto_migrated_v2731", true);
            }
        } catch (Throwable ignored) {
        }
        putDefault(HawkConfig.PLAY_TYPE, PlayerAuto.TYPE_AUTO);  //播放器: 4=智能, 0=系统, 1=IJK, 2=Exo, 10=MX, 11=Reex, 12=Kodi
        // v27.30: m3u8 去广告默认关闭 —— 开启时起播前要多下载 1~2 遍 m3u8, 等于每次播放多一个往返
        putDefault(HawkConfig.VIDEO_PURIFY, false);
        putDefault(HawkConfig.IJK_CODEC, "硬解码");           //IJK解码: 软解码, 硬解码
        // 系统选项
        putDefault(HawkConfig.HOME_LOCALE, 0);               //语言: 0=中文, 1=英文
        putDefault(HawkConfig.THEME_SELECT, 0);              //主题: 0=奈飞, 1=哆啦, 2=百事, 3=鸣人, 4=小黄, 5=八神, 6=樱花
        putDefault(HawkConfig.SEARCH_VIEW, 1);               //搜索展示: 0=文字列表, 1=缩略图
        putDefault(HawkConfig.PARSE_WEBVIEW, true);          //嗅探Webview: true=系统自带, false=XWalkView
        putDefault(HawkConfig.DOH_URL, 0);                   //安全DNS: 0=关闭, 1=腾讯, 2=阿里, 3=360, 4=Google, 5=AdGuard, 6=Quad9

    }

    private void initLocale() {
        if (Hawk.get(HawkConfig.HOME_LOCALE, 0) == 0) {
            LocaleHelper.setLocale(App.this, "zh");
        } else {
            LocaleHelper.setLocale(App.this, "");
        }
    }

    public static App getInstance() {
        return instance;
    }

    private void putDefault(String key, Object value) {
        if (!Hawk.contains(key)) {
            Hawk.put(key, value);
        }
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        JsLoader.destroy();
    }

    public void setDashData(String data) {
        dashData = data;
    }

    public String getDashData() {
        return dashData;
    }

    public static void startWebserver() {
        if (server != null) return;
        server = AndServer
                .webServer(instance)
                .port(12345)
                .timeout(60, TimeUnit.SECONDS)
                .listener(new Server.ServerListener() {
                    @Override
                    public void onStarted() {

                    }

                    @Override
                    public void onStopped() {

                    }

                    @Override
                    public void onException(Exception e) {

                    }
                }).build();
        server.startup();
    }

    public static void post(Runnable runnable) {
        getInstance().handler.post(runnable);
    }

    public static void post(Runnable runnable, long delayMillis) {
        getInstance().handler.removeCallbacks(runnable);
        if (delayMillis >= 0) getInstance().handler.postDelayed(runnable, delayMillis);
    }
}
