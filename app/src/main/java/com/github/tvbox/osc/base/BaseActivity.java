package com.github.tvbox.osc.base;

import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.PermissionChecker;
import com.blankj.utilcode.util.ActivityUtils;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.callback.EmptyCallback;
import com.github.tvbox.osc.callback.LoadingCallback;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.util.AppManager;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LocaleHelper;
import com.kingja.loadsir.callback.Callback;
import com.kingja.loadsir.core.LoadService;
import com.kingja.loadsir.core.LoadSir;
import com.orhanobut.hawk.Hawk;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;

import io.github.inflationx.viewpump.ViewPumpContextWrapper;
import me.jessyan.autosize.AutoSizeCompat;
import me.jessyan.autosize.internal.CustomAdapt;
import xyz.doikki.videoplayer.util.CutoutUtil;

/**
 * @author pj567
 * @date :2020/12/17
 * @description:
 */
public abstract class BaseActivity extends AppCompatActivity implements CustomAdapt {
    protected Context mContext;
    private LoadService mLoadService;

    private static float screenRatio = -100.0f;

    // takagen99 : Fix for Locale change not persist on higher Android version
    @Override
    protected void attachBaseContext(Context base) {
        Context newBase = base;
        if (App.viewPump != null) {
            newBase = ViewPumpContextWrapper.wrap(base, App.viewPump);
        }

        if (Hawk.get(HawkConfig.HOME_LOCALE, 0) == 0) {
            super.attachBaseContext(LocaleHelper.onAttach(newBase, "zh"));
        } else {
            super.attachBaseContext(LocaleHelper.onAttach(newBase, ""));
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        try {
            if (screenRatio < 0) {
                DisplayMetrics dm = new DisplayMetrics();
                getWindowManager().getDefaultDisplay().getMetrics(dm);
                int screenWidth = dm.widthPixels;
                int screenHeight = dm.heightPixels;
                screenRatio = (float) Math.max(screenWidth, screenHeight) / (float) Math.min(screenWidth, screenHeight);
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }

        // takagen99 : Set Theme Color
        // 小贾影视仓 v24.7.2: 中转页不再 setTheme 整套换主题 —— 部分 EMUI/华为机型上换主题后
        // 框架 TextAppearance 的 ?android:attr/textColorLink 解析失败, inflate 崩溃。
        // 改为: 先正常选皮肤主题, 再 applyStyle 仅叠加窗口透明/无动画属性。
        if (Hawk.get(HawkConfig.THEME_SELECT, 0) == 0) {
            setTheme(R.style.NetfxTheme);
        } else if (Hawk.get(HawkConfig.THEME_SELECT, 0) == 1) {
            setTheme(R.style.DoraeTheme);
        } else if (Hawk.get(HawkConfig.THEME_SELECT, 0) == 2) {
            setTheme(R.style.PepsiTheme);
        } else if (Hawk.get(HawkConfig.THEME_SELECT, 0) == 3) {
            setTheme(R.style.NarutoTheme);
        } else if (Hawk.get(HawkConfig.THEME_SELECT, 0) == 4) {
            setTheme(R.style.MinionTheme);
        } else if (Hawk.get(HawkConfig.THEME_SELECT, 0) == 5) {
            setTheme(R.style.YagamiTheme);
        } else {
            setTheme(R.style.SakuraTheme);
        }
        if (passThroughWindow()) {
            getTheme().applyStyle(R.style.XiaojiaPassThroughStyle, true);
        }
        if (opaqueNoWindowAnim()) {
            // 小贾影视仓 v24.9: 播放页 —— 窗口必须**不透明**。
            // AppTheme.NoActionBar 的 windowBackground 是 transparent, 全 app 都是半透明窗口;
            // 播放页叠上去会直接看穿到下层的详情页, 这才是"闪一下旧小窗"的真凶(杀动画无效)。
            getTheme().applyStyle(R.style.XiaojiaOpaqueNoAnimStyle, true);
        }

        super.onCreate(savedInstanceState);
        if (passThroughWindow()) {
            overridePendingTransition(0, 0); // 中转页无缝进场
        }
        if (opaqueNoWindowAnim()) {
            killOwnTransitions(); // v24.8: 进出场动画多重保险(API 21~34+)
        }
        setContentView(getLayoutResID());
        mContext = this;
        CutoutUtil.adaptCutoutAboveAndroidP(mContext, true);//设置刘海
        AppManager.getInstance().addActivity(this);
        init();
        setScreenOn();
    }

    /**
     * 小贾影视仓 v24.7.2: 中转页透明化钩子。
     * 返回 true 时在皮肤主题上 applyStyle 叠加 XiaojiaPassThroughStyle(半透明+无窗口动画),
     * 用于"只拉数据、随即跳走"的中转页(如 DetailActivity 的 autoPlay 直达播放模式),
     * 消灭进出场缩放动画造成的"闪一下旧小窗"。不再 setTheme 整套换主题(EMUI 兼容)。
     */
    protected boolean passThroughWindow() {
        return false;
    }

    /**
     * 小贾影视仓 v24.8: 杀自身窗口进出场动画钩子(窗口不透明)。
     * 返回 true 时叠加 XiaojiaNoAnimStyle + overridePendingTransition/overrideActivityTransition 双保险。
     */
    protected boolean noWindowAnim() {
        return false;
    }

    /**
     * 小贾影视仓 v24.9: 播放页专用 —— 不透明窗口 + 无进出场动画。
     * 【真正的病根】AppTheme.NoActionBar 声明了 windowBackground=@android:color/transparent,
     * 全 app 所有 Activity 都是半透明窗口。PlayActivity 叠在 DetailActivity/列表页之上时,
     * 下层旧画面会直接透过播放页窗口显示出来 —— 这正是用户反复报告的
     "点封面到竖屏播放之间闪一下以前的小屏界面"。此前三版(v24.6/v24.7.2/v24.8)分别用
     setTheme 换主题 / applyStyle 透明 / overrideActivityTransition 杀动画, 全部无效,
     因为问题从来不是动画, 而是窗口透明。
     */
    protected boolean opaqueNoWindowAnim() {
        return false;
    }

    /** 小贾影视仓 v24.8: 兼容 API 21~34+ 的进出场动画关闭。 */
    private void killOwnTransitions() {
        try {
            overridePendingTransition(0, 0);
        } catch (Throwable ignored) {
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0);
                overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI(true);
        changeWallpaper(false);
    }

    // takagen99 : Check for Gesture or 3-Buttons NavBar
    // 0 : 3-Button NavBar
    // 1 : 2-Button NavBar (Android P)
    // 2 : Gesture full screen
    public static int isEdgeToEdgeEnabled(Context context) {
        Resources resources = context.getResources();
        int resourceId = resources.getIdentifier("config_navBarInteractionMode", "integer", "android");
        if (resourceId > 0) {
            return resources.getInteger(resourceId);
        }
        return 0;
    }

    public void hideSysBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            int uiOptions = getWindow().getDecorView().getSystemUiVisibility();
            uiOptions |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
            //    uiOptions |= View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            uiOptions |= View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            //    uiOptions |= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
            uiOptions |= View.SYSTEM_UI_FLAG_FULLSCREEN;
            uiOptions |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            getWindow().getDecorView().setSystemUiVisibility(uiOptions);
        }
    }

    public void vidHideSysBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            int uiOptions = getWindow().getDecorView().getSystemUiVisibility();
            uiOptions |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
            uiOptions |= View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            uiOptions |= View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            uiOptions |= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
            uiOptions |= View.SYSTEM_UI_FLAG_FULLSCREEN;
            uiOptions |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            getWindow().getDecorView().setSystemUiVisibility(uiOptions);
        }
    }

    public void hideSystemUI(boolean shownavbar) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            int uiVisibility = getWindow().getDecorView().getSystemUiVisibility();
            uiVisibility |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
            uiVisibility |= View.SYSTEM_UI_FLAG_LOW_PROFILE;
            uiVisibility |= View.SYSTEM_UI_FLAG_FULLSCREEN;
            uiVisibility |= View.SYSTEM_UI_FLAG_IMMERSIVE;
            uiVisibility |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            if (!shownavbar) {
                uiVisibility |= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
                uiVisibility |= View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            }
            getWindow().getDecorView().setSystemUiVisibility(uiVisibility);
            // set content behind navigation bar
//        getWindow().setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        }
    }

    public void showSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            int uiVisibility = getWindow().getDecorView().getSystemUiVisibility();
            uiVisibility &= ~View.SYSTEM_UI_FLAG_LOW_PROFILE;
            uiVisibility &= ~View.SYSTEM_UI_FLAG_FULLSCREEN;
            uiVisibility &= ~View.SYSTEM_UI_FLAG_IMMERSIVE;
            uiVisibility &= ~View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            uiVisibility &= ~View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
            uiVisibility &= ~View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            getWindow().getDecorView().setSystemUiVisibility(uiVisibility);
        }
    }

    @Override
    public Resources getResources() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            AutoSizeCompat.autoConvertDensityOfCustomAdapt(super.getResources(), this);
        }
        return super.getResources();
    }

    public boolean hasPermission(String permission) {
        boolean has = true;
        try {
            has = PermissionChecker.checkSelfPermission(this, permission) == PermissionChecker.PERMISSION_GRANTED;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return has;
    }

    protected abstract int getLayoutResID();

    protected abstract void init();

    protected void setLoadSir(View view) {
        if (mLoadService == null) {
            mLoadService = LoadSir.getDefault().register(view, new Callback.OnReloadListener() {
                @Override
                public void onReload(View v) {
                }
            });
        }
    }

    protected void showLoading() {
        if (mLoadService != null) {
            mLoadService.showCallback(LoadingCallback.class);
        }
    }

    protected boolean isLoading() {
        if (mLoadService != null && mLoadService.getCurrentCallback() != null) {
            return mLoadService.getCurrentCallback().equals(LoadingCallback.class);
        }
        return false;
    }

    protected void showEmpty() {
        if (null != mLoadService) {
            mLoadService.showCallback(EmptyCallback.class);
        }
    }

    protected void showSuccess() {
        if (null != mLoadService) {
            mLoadService.showSuccess();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        AppManager.getInstance().finishActivity(this);
    }

    public void jumpActivity(Class<? extends BaseActivity> clazz) {
        Intent intent = new Intent(mContext, clazz);
        startActivity(intent);
    }

    public void jumpActivity(Class<? extends BaseActivity> clazz, Bundle bundle) {
    	if (DetailActivity.class.isAssignableFrom(clazz) && Hawk.get(HawkConfig.BACKGROUND_PLAY_TYPE, 0) == 2) {
            //1.重新打开singleTask的页面(关闭小窗) 2.关闭画中画，重进detail再开启画中画会闪退
            ActivityUtils.finishActivity(DetailActivity.class);
        }
        Intent intent = new Intent(mContext, clazz);
        intent.putExtras(bundle);
        startActivity(intent);
    }

    protected String getAssetText(String fileName) {
        StringBuilder stringBuilder = new StringBuilder();
        try {
            AssetManager assets = getAssets();
            BufferedReader bf = new BufferedReader(new InputStreamReader(assets.open(fileName)));
            String line;
            while ((line = bf.readLine()) != null) {
                stringBuilder.append(line);
            }
            return stringBuilder.toString();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return "";
    }

    @Override
    public float getSizeInDp() {
        return isBaseOnWidth() ? 1280 : 720;
    }

    @Override
    public boolean isBaseOnWidth() {
        return !(screenRatio >= 4.0f);
    }

    public boolean supportsPiPMode() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    public boolean supportsTouch() {
        return getPackageManager().hasSystemFeature("android.hardware.touchscreen");
    }

    public void setScreenBrightness(float amt) {
        WindowManager.LayoutParams lparams = getWindow().getAttributes();
        lparams.screenBrightness = amt;
        getWindow().setAttributes(lparams);
    }

    public void setScreenOn() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    public void setScreenOff() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // takagen99: Added Theme Color
    public int getThemeColor() {
        TypedArray a = mContext.obtainStyledAttributes(R.styleable.themeColor);
        int themeColor = a.getColor(R.styleable.themeColor_color_theme, 0);
        return themeColor;
    }

    // 小贾影视仓 v25: 缓存**Bitmap**(不可变, 可安全跨 Activity 共享), 不再缓存 Drawable。
    // 【壁纸只画上半屏的根因】旧代码缓存的是 BitmapDrawable 实例(static globalWp), 而 Drawable 是
    // **有状态的** —— 它的 bounds 由窗口在绘制时写入。一个 Drawable 实例被先后 setBackgroundDrawable
    // 给多个窗口(首页/搜索/详情/播放)时, bounds 会残留上一次窗口的尺寸; 某些机型上窗口首帧还没
    // 完成 measure 就取到旧 bounds, 于是壁纸按旧高度画一半, 下面露出黑底 —— 用户看到的
    // "加载推荐列表时壁纸缩到一半"(带壁纸/无壁纸两个版本都会出现, 因为 ColorDrawable 同样是
    // 同一个被共享的实例)。改成缓存 Bitmap、每个 Activity 现造 BitmapDrawable 并显式 FILL,
    // 彻底断掉 Drawable 状态的跨窗口污染。
    protected static Bitmap globalWpBitmap = null;

    private android.graphics.drawable.Drawable buildWallpaperDrawable() {
        if (globalWpBitmap == null) return null;
        android.graphics.drawable.BitmapDrawable d =
                new android.graphics.drawable.BitmapDrawable(getResources(), globalWpBitmap);
        d.setGravity(android.view.Gravity.FILL);
        return d;
    }

    // 小贾影视仓: 壁纸压暗, 让白色文字清晰
    private static Bitmap darkenWallpaper(Bitmap src) {
        if (src == null) return null;
        try {
            Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(out);
            c.drawBitmap(src, 0f, 0f, null);
            // 小贾影视仓 v24.3: 恢复 45%(alpha 115) 压暗 —— 原壁纸(用户照片)较亮,
            // v24.2 降到 25% 后白字/半透卡片在亮壁纸上几乎看不清(用户反馈"推荐没有了"的观感原因之一)。
            // 本壁纸的原设计(v23)就是 45%, 实测可读性最佳。
            c.drawColor(android.graphics.Color.argb(115, 0, 0, 0));
            return out;
        } catch (Throwable t) {
            return src;
        }
    }

    // 小贾影视仓 v23: 按屏幕比例"居中裁剪"壁纸(等价 ImageView.ScaleType.CENTER_CROP)。
    // 为什么需要: window 背景的 BitmapDrawable 默认是 FILL(拉伸铺满), 而内置壁纸已换成 3:4 竖图、
    // 手机屏约 9:20 —— 不预裁的话人像会被纵向拉长约 1.7 倍。先裁到屏幕比例, 再交给 window 铺满即可。
    private Bitmap centerCropWallpaper(Bitmap src) {
        if (src == null) return null;
        try {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            int vw = dm.widthPixels, vh = dm.heightPixels;
            int w = src.getWidth(), h = src.getHeight();
            if (vw <= 0 || vh <= 0 || w <= 0 || h <= 0) return src;
            float target = vw / (float) vh;     // 屏幕宽高比(竖屏 < 1)
            float current = w / (float) h;      // 原图宽高比
            int cw, ch;
            if (current > target) {
                // 原图比屏幕"更宽" -> 裁掉左右, 高度全保留
                ch = h;
                cw = Math.round(h * target);
            } else {
                // 原图比屏幕"更窄/更高" -> 裁掉上下, 宽度全保留
                cw = w;
                ch = Math.round(w / target);
            }
            if (cw == w && ch == h) return src;
            int x = Math.max(0, (w - cw) / 2);
            int y = Math.max(0, (h - ch) / 2);
            Bitmap out = Bitmap.createBitmap(src, x, y, cw, ch);
            return out == null ? src : out;
        } catch (Throwable t) {
            return src;
        }
    }

    public void changeWallpaper(boolean force) {
        // 小贾影视仓 v25: 无壁纸版(BuildConfig.XJ_WALLPAPER=false)直接走主题纯色 ——
        // 这个判断必须放在**读 filesDir/wp 和解码任何 Bitmap 之前**, 否则:
        //   1) 无壁纸版用户曾在设置里下载过壁纸 → filesDir/wp 还在 → 照样画壁纸, 违背"无壁纸版"定义;
        //   2) 每次进页面都要 decodeFile + darken + centerCrop, 白白拖慢启动。
        boolean withWp;
        try {
            withWp = com.github.tvbox.osc.BuildConfig.XJ_WALLPAPER;
        } catch (Throwable t) {
            withWp = true; // debug/无字段时保持原行为
        }
        if (!withWp) {
            globalWpBitmap = null;
            applyThemeColorBackground();
            return;
        }
        // v25: 快速路径也要**每次新建 Drawable**, 不能复用上一次的实例 ——
        // 共享 Drawable 会带着上个窗口的 bounds 画出半截壁纸。
        if (!force && globalWpBitmap != null) {
            android.graphics.drawable.Drawable d = buildWallpaperDrawable();
            if (d != null) {
                getWindow().setBackgroundDrawable(d);
                return;
            }
        }
        try {
            File wp = new File(getFilesDir().getAbsolutePath() + "/wp");
            if (wp.exists()) {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(wp.getAbsolutePath(), opts);
                // 从Options中获取图片的分辨率
                int imageHeight = opts.outHeight;
                int imageWidth = opts.outWidth;
                // 小贾影视仓 v23: 基准从横屏 1080x720 改成竖屏 1080x1920 —— 否则 3:4 竖图会被算成 scale=2 白白降采样
                int picHeight = 1920;
                int picWidth = 1080;
                int scaleX = imageWidth / picWidth;
                int scaleY = imageHeight / picHeight;
                int scale = Math.max(Math.max(scaleX, scaleY), 1);
                opts.inJustDecodeBounds = false;
                // 采样率
                opts.inSampleSize = scale;
                Bitmap src = BitmapFactory.decodeFile(wp.getAbsolutePath(), opts);
                if (src == null) {
                    globalWpBitmap = null;
                } else {
                    globalWpBitmap = darkenWallpaper(centerCropWallpaper(src));
                }
            } else {
                globalWpBitmap = null;
            }
        } catch (Throwable throwable) {
            throwable.printStackTrace();
            globalWpBitmap = null;
        }
        if (globalWpBitmap != null) {
            android.graphics.drawable.Drawable d = buildWallpaperDrawable();
            if (d != null) getWindow().setBackgroundDrawable(d);
        } else {
            // 小贾影视仓: 无线路壁纸时回退到内置壁纸(home_wallpaper=用户美女图), 采样压缩+压暗, 失败再纯色(绝不黑屏)
            // v15: BuildConfig.XJ_WALLPAPER 构建开关已在方法开头判过, 到这里必然是带壁纸版
            try {
                android.content.res.Resources res = getResources();
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeResource(res, R.drawable.home_wallpaper, opts);
                // 小贾影视仓 v23: 基准同步改竖屏(原 1080x720 是横屏设计稿尺寸)
                int scale = Math.max(Math.max(opts.outWidth / 1080, opts.outHeight / 1920), 1);
                opts.inJustDecodeBounds = false;
                opts.inSampleSize = scale;
                Bitmap bmp = BitmapFactory.decodeResource(res, R.drawable.home_wallpaper, opts);
                if (bmp != null) {
                    globalWpBitmap = darkenWallpaper(centerCropWallpaper(bmp));
                    android.graphics.drawable.Drawable d = buildWallpaperDrawable();
                    if (d != null) getWindow().setBackgroundDrawable(d);
                    return;
                }
            } catch (Throwable t) {
                t.printStackTrace();
            }
            // 小贾影视仓 v15.10: 内置壁纸回退失败(bmp==null 或 catch 后), 不再掉到 color_theme 主题色兜底
            // (默认 NetfxTheme=#D81F26=红色), 直接 return, window 背景保持 styl transparent 绝不出红屏
            return;
        }
    }

    // 无壁纸版唯一的绘制路径: 主题色纯色背景(不读盘、不解码 Bitmap)
    private void applyThemeColorBackground() {
        try {
            android.util.TypedValue tv = new android.util.TypedValue();
            getTheme().resolveAttribute(R.attr.color_theme, tv, true);
            int themeColor;
            if (tv.resourceId != 0) {
                themeColor = getResources().getColor(tv.resourceId);
            } else {
                themeColor = tv.data;
            }
            // v25: ColorDrawable 每次新建, 不用 static 共享实例(与壁纸同一个坑)
            getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(themeColor));
        } catch (Throwable t) {
            getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF101010));
        }
    }
}
