package com.github.tvbox.osc.ui.activity;

import android.Manifest;
import android.content.Context;
import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.IntEvaluator;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.BounceInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.fragment.app.FragmentTransaction;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager.widget.ViewPager;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.bean.AbsSortXml;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.ui.adapter.HomePageAdapter;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.github.tvbox.osc.ui.adapter.SortAdapter;
import com.github.tvbox.osc.ui.dialog.ApiDialog;
import com.github.tvbox.osc.ui.dialog.SelectDialog;
import com.github.tvbox.osc.ui.dialog.SourcePanelDialog;
import com.github.tvbox.osc.ui.dialog.TipDialog;
import com.github.tvbox.osc.ui.fragment.GridFragment;
import com.github.tvbox.osc.ui.fragment.UserFragment;
import com.github.tvbox.osc.ui.tv.widget.DefaultTransformer;
import com.github.tvbox.osc.ui.tv.widget.FixedSpeedScroller;
import com.github.tvbox.osc.ui.tv.widget.NoScrollViewPager;
import com.github.tvbox.osc.ui.tv.widget.ViewObj;
import com.github.tvbox.osc.util.AppManager;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.lzy.okgo.OkGo;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import me.jessyan.autosize.utils.AutoSizeUtils;

public class HomeActivity extends BaseActivity {

    // takagen99: Added to allow read string
    private static Resources res;

    private View currentView;
    private LinearLayout topLayout;
    private LinearLayout contentLayout;
    private TextView tvName;
    private ImageView tvFind;
    private ImageView tvMenu;
    private ImageView tvLive;
    // 小贾影视仓 v20: 首页通栏搜索框(取代原顶栏"打开应用"位置, 原抽屉入口已移除)
    private LinearLayout homeSearchBar;
    private TvRecyclerView mGridView;
    private NoScrollViewPager mViewPager;
    private SourceViewModel sourceViewModel;
    private SortAdapter sortAdapter;
    private HomePageAdapter pageAdapter;
    private final List<BaseLazyFragment> fragments = new ArrayList<>();
    private boolean isDownOrUp = false;
    private boolean sortChange = false;
    private int currentSelected = 0;
    private int sortFocused = 0;
    public View sortFocusView = null;
    private final Handler mHandler = new Handler();
    private long mExitTime = 0;
    // 小贾影视仓 v15.4: 移除顶栏时钟 ticker(原 tvDate 控件已随顶栏瘦身删除)

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_home;
    }

    boolean useCacheConfig = false;

    @Override
    protected void init() {
        // takagen99: Added to allow read string
        res = getResources();

        EventBus.getDefault().register(this);
        ControlManager.get().startServer();
        App.startWebserver();
        initView();
        initViewModel();
        useCacheConfig = false;
        Intent intent = getIntent();
        if (intent != null && intent.getExtras() != null) {
            Bundle bundle = intent.getExtras();
            useCacheConfig = bundle.getBoolean("useCache", false);
        }
        // 小贾影视仓 v21: 「内置·肥猫全能包·120站」已下线(内置加固包在部分机型点击即 native 崩)。
        // 老用户配置里若还指着它, 这里静默回退到默认线路并清掉该历史项, 免得一进 App/一联网就踩雷。
        try {
            String oldApi = Hawk.get(HawkConfig.API_URL, "");
            if (oldApi != null && oldApi.contains("feimao/config.json")) {
                Hawk.put(HawkConfig.API_URL, getString(R.string.app_source));
                ArrayList<String> hist = Hawk.get(HawkConfig.API_HISTORY, new ArrayList<String>());
                if (hist != null) {
                    for (int i = hist.size() - 1; i >= 0; i--) {
                        String h = hist.get(i);
                        if (h != null && h.contains("feimao/config.json")) hist.remove(i);
                    }
                    Hawk.put(HawkConfig.API_HISTORY, hist);
                }
            }
        } catch (Throwable ignored) {
        }
        initData();
    }

    // takagen99: Added to allow read string
    public static Resources getRes() {
        return res;
    }

    private void initView() {
        this.topLayout = findViewById(R.id.topLayout);
        this.tvName = findViewById(R.id.tvName);
        this.tvFind = findViewById(R.id.tvFind);
        this.tvMenu = findViewById(R.id.tvMenu);
        this.tvLive = findViewById(R.id.tvLive);
        this.homeSearchBar = findViewById(R.id.homeSearchBar);
        this.contentLayout = findViewById(R.id.contentLayout);
        this.mGridView = findViewById(R.id.mGridViewCategory);
        this.mViewPager = findViewById(R.id.mViewPager);
        this.sortAdapter = new SortAdapter();
        this.mGridView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 0, false));
        this.mGridView.setSpacingWithMargins(0, AutoSizeUtils.dp2px(this.mContext, 10.0f));
        this.mGridView.setAdapter(this.sortAdapter);
        sortAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                mGridView.post(() -> {
                    View firstChild = Objects.requireNonNull(mGridView.getLayoutManager()).findViewByPosition(0);
                    if (firstChild != null) {
                        mGridView.setSelectedPosition(0);
                        firstChild.requestFocus();
                    }
                });
            }
        });
        this.mGridView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            public void onItemPreSelected(TvRecyclerView tvRecyclerView, View view, int position) {
                if (view != null && !HomeActivity.this.isDownOrUp) {
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(250).start();
                    TextView textView = view.findViewById(R.id.tvTitle);
                    textView.getPaint().setFakeBoldText(false);
                    textView.setTextColor(HomeActivity.this.getResources().getColor(R.color.color_FFFFFF_70));
                    textView.invalidate();
                    view.findViewById(R.id.tvFilter).setVisibility(View.GONE);
                }
            }

            public void onItemSelected(TvRecyclerView tvRecyclerView, View view, int position) {
                if (view != null) {
                    HomeActivity.this.currentView = view;
                    HomeActivity.this.isDownOrUp = false;
                    HomeActivity.this.sortChange = true;
                    view.animate().scaleX(1.06f).scaleY(1.06f).setInterpolator(new BounceInterpolator()).setDuration(250).start();
                    TextView textView = view.findViewById(R.id.tvTitle);
                    textView.getPaint().setFakeBoldText(true);
                    textView.setTextColor(HomeActivity.this.getResources().getColor(R.color.color_FFFFFF));
                    textView.invalidate();
//                    if (!sortAdapter.getItem(position).filters.isEmpty())
//                        view.findViewById(R.id.tvFilter).setVisibility(View.VISIBLE);
                    if (position == -1) {
                        position = 0;
                        HomeActivity.this.mGridView.setSelection(0);
                    }
                    MovieSort.SortData sortData = sortAdapter.getItem(position);
                    if (null != sortData && !sortData.filters.isEmpty()) {
                        showFilterIcon(sortData.filterSelectCount());
                    }
                    HomeActivity.this.sortFocusView = view;
                    HomeActivity.this.sortFocused = position;
                    mHandler.removeCallbacks(mDataRunnable);
                    mHandler.postDelayed(mDataRunnable, 200);
                }
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
                if (itemView != null && currentSelected == position) {
                    BaseLazyFragment baseLazyFragment = fragments.get(currentSelected);
                    if ((baseLazyFragment instanceof GridFragment) && !sortAdapter.getItem(position).filters.isEmpty()) {// 弹出筛选
                        ((GridFragment) baseLazyFragment).showFilter();
                    } else if (baseLazyFragment instanceof UserFragment) {
                        showSiteSwitch();
                    }
                }
            }
        });
        this.mGridView.setOnInBorderKeyEventListener(new TvRecyclerView.OnInBorderKeyEventListener() {
            // 小贾影视仓 v15.7: 分类改回顶部胶囊横排(方案A) —— 边界语义恢复原版 TVBox:
            //   UP   = 顶到第一个分类仍按上 -> 刷新当前分类, 放行走回顶栏
            //   DOWN = 从导航进入下方内容区(数据没加载完则拦截, 避免空页抢焦点)
            //   LEFT / RIGHT = 放行(横向行内滚动由 TvRecyclerView 自身处理)
            public boolean onInBorderKeyEvent(int direction, View view) {
                BaseLazyFragment baseLazyFragment = (sortFocused >= 0 && sortFocused < fragments.size()) ? fragments.get(sortFocused) : null;
                if (direction == View.FOCUS_UP) {
                    if (baseLazyFragment instanceof GridFragment) {
                        ((GridFragment) baseLazyFragment).forceRefresh();
                    }
                    return false;
                }
                if (direction != View.FOCUS_DOWN) {
                    return false;
                }
                if (!(baseLazyFragment instanceof GridFragment)) {
                    return false;
                }
                return !((GridFragment) baseLazyFragment).isLoad();
            }
        });
        // 小贾影视仓 v15.4: 单击标题 = 打开统一「信号源」面板(原"清缓存"语义移入面板操作项)
        tvName.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                showSiteSwitch();
            }
        });
        tvName.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                reloadHomeFresh();
                return true;
            }
        });
        // 小贾影视仓 v15.4: 移除顶栏 WiFi 快捷入口(WiFi 设置走系统, 面板聚焦信号源)
        // Button : Search --------------------------------------------
        tvFind.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                jumpActivity(SearchActivity.class);
            }
        });
        // 小贾影视仓 v20: 首页通栏搜索框 —— 独立入口, 点击直达搜索页
        // (原路径: 顶栏小放大镜图标 / 底部「更多」面板里的搜索项, 均保留但不再是唯一入口)
        homeSearchBar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                jumpActivity(SearchActivity.class);
            }
        });
        // 小贾影视仓 v15.4: 首页样式开关迁入「信号源」面板(toggleHomeStyle), 顶栏只留源胶囊+3 图标
        // 小贾影视仓 v20: 移除顶栏「打开应用」(AppsActivity 抽屉) —— 首页不再提供该入口, 顶栏=标题+搜索+设置
        // Button : Settings >> To go into Settings --------------------
        tvMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                jumpActivity(SettingActivity.class);
            }
        });
        // 小贾影视仓 v23: 直播入口(从底部 dock 上移至顶栏, 紧挨设置)
        tvLive.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                jumpActivity(LivePlayActivity.class);
            }
        });
        // Button : Settings >> To go into App Settings ----------------
        tvMenu.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null)));
                return true;
            }
        });
        // 小贾影视仓 v15.4: 线路选择迁入「信号源」面板(buildLineCandidates / applyLineSelection)
        // 小贾影视仓 v15.4: 线路配置迁入「信号源」面板(openConfigManager)
        setLoadSir(this.contentLayout);
        //mHandler.postDelayed(mFindFocus, 250);
    }
    //站点切换
    public static void homeRecf() {
        int homeRec = Hawk.get(HawkConfig.HOME_REC, -1);
        int limit = 2;
        if (homeRec == limit) homeRec = -1;
        homeRec++;
        Hawk.put(HawkConfig.HOME_REC, homeRec);
    }
    
    public static boolean reHome(Context appContext) {
        Intent intent = new Intent(appContext, HomeActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Bundle bundle = new Bundle();
        bundle.putBoolean("useCache", true);
        intent.putExtras(bundle);
        appContext.startActivity(intent);
        return true;
    }

    private boolean skipNextUpdate = false;
    // 小贾影视仓: 本地接口文件选择请求码(ApiDialog"本地文件"按钮发起)
    private static final int REQ_PICK_LOCAL_API = 0x5150;
    // 小贾影视仓 v21: 本地 .py 单源选择请求码(信号源面板"加载本地 .py"按钮发起)
    private static final int REQ_PICK_LOCAL_PY = 0x5151;
    private void initViewModel() {
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        sourceViewModel.sortResult.observe(this, new Observer<AbsSortXml>() {
            @Override
            public void onChanged(AbsSortXml absXml) {
                if (skipNextUpdate) {
                    skipNextUpdate = false;
                    return;
                }
                // v15.4: 原地切站期间, 只接受与当前 home 站点一致的分类结果, 过期/串台结果直接丢弃
                // 注: getSort 结果不携带站点标记, 极端连点(A->B->C 且前序请求仍在途)时旧结果可能先到并被
                // 短暂采纳, 真正结果到场即纠正(自愈); 切站失败则回滚原站 + 重启兜底, 不丢首页。
                String guard = homeSortGuard;
                SourceBean guardHome = ApiConfig.get().getHomeSourceBean();
                if (guard != null && (guardHome == null || !guard.equals(guardHome.getKey()))) {
                    homeSortGuard = null;
                    return;
                }
                boolean fromHomeSwitch = (guard != null);
                homeSortGuard = null;
                if (fromHomeSwitch) {
                    // 原地切站结果到场: 先复位分类选中位, initViewPager 将按新站数据重建页面
                    inPlaceSwitching = false;
                    if (absXml == null) {
                        // 切站失败(网络/超时/解析空) -> 回滚原站点并走重启兜底, 避免白屏丢首页
                        String prevKey = homeSwitchPrevKey;
                        homeSwitchPrevKey = null;
                        SourceBean prev = prevKey == null ? null : ApiConfig.get().getSource(prevKey);
                        if (prev != null) {
                            ApiConfig.get().setSourceBean(prev);
                            Toast.makeText(HomeActivity.this, "切站失败, 已回退到「" + prev.getName() + "」", Toast.LENGTH_SHORT).show();
                            restartHome(true);
                            return;
                        }
                    }
                    currentSelected = 0;
                    sortFocused = 0;
                    if (mGridView != null) mGridView.setSelection(0);
                }
                showSuccess();
                cancelSortWatchdog();
                // 小贾影视仓 v15.15: getHomeSourceBean() 判空 —— 配置加载失败时该对象为 null,
                // 原代码直接 .getKey() 会 NPE 闪退(启动期最常见的一处裸奔点)。
                SourceBean homeSortBean = ApiConfig.get().getHomeSourceBean();
                String homeSortKey = homeSortBean == null ? "" : homeSortBean.getKey();
                if (absXml != null && absXml.classes != null && absXml.classes.sortList != null) {
                    sortAdapter.setNewData(DefaultConfig.adjustSort(homeSortKey, absXml.classes.sortList, true));
                } else {
                    sortAdapter.setNewData(DefaultConfig.adjustSort(homeSortKey, new ArrayList<>(), true));
                }
                initViewPager(absXml);
                // v15.15: 首页数据已到位 => 8 秒后判定本次启动成功, 清掉启动哨兵标记(native 崩溃不会清 => 下次启动计数+1)
                postDelayedIfAlive(new Runnable() {
                    @Override
                    public void run() {
                        markBootOk();
                    }
                }, 8000);
                // 小贾影视仓 v15.14: 首页"推荐影视"(my0) 空数据自愈 —— 站点推荐模式(默认)下 getSort
                // 返回的分类里若 videoList 为空(推荐兜底超时/失败/缓存也空), 先正常注入, 再延迟重拉一次。
                // getSort 二次执行时会优先命中按天落盘缓存回填; guard 保证单 Activity 生命周期内至多重拉一次, 不构成死循环。
                if (absXml != null && Hawk.get(HawkConfig.HOME_REC, 0) == 1
                        && (absXml.videoList == null || absXml.videoList.isEmpty())
                        && !homeRecSelfHealFired) {
                    homeRecSelfHealFired = true;
                    final SourceBean healHome = ApiConfig.get().getHomeSourceBean();
                    postDelayedIfAlive(new Runnable() {
                        @Override
                        public void run() {
                            if (healHome != null && alive()) {
                                sourceViewModel.getSort(healHome.getKey());
                            }
                        }
                    }, 1500);
                }
                // 小贾影视仓: 标题显示当前线路名(红底白字)
                tvName.setText(getString(R.string.app_name) + " · " + getLineName(Hawk.get(HawkConfig.API_URL, getString(R.string.app_source))));
                tvName.clearAnimation();
                if (fromHomeSwitch) {
                    // 复位后, ViewPager 视口回到第 0 页
                    postIfAlive(new Runnable() {
                        @Override
                        public void run() {
                            if (mViewPager != null) mViewPager.setCurrentItem(0, false);
                        }
                    });
                }
            }
        });
    }

    private boolean dataInitOk = false;
    private boolean jarInitOk = false;

    // 小贾影视仓 v15.4: 原地切源守卫 + 分类结果防串台(homeSortGuard=期望生效的站点key, 过期结果直接丢弃)
    private boolean inPlaceSwitching = false;
    private volatile String homeSortGuard = null;
    // 小贾影视仓 v15.14: 首页推荐空数据自愈 guard(单 Activity 生命周期内至多重拉一次)
    private boolean homeRecSelfHealFired = false;

    // 小贾影视仓 v18: getSort "结果永远不到场"的兜底。
    // spThreadPool(4 线程)若被多个长任务(详情/推荐兜底)占满, sortResult.postValue 会长时间不执行,
    // 首页就一直停在 LoadingCallback 转圈 —— 即用户反馈的"一直加载影视封面"。
    // 到点仍在 loading 就直接结束加载, 并借已有的"推荐空→重拉"自愈再触发一次 getSort(guard 保证不自旋)。
    private Runnable sortWatchdog;

    private void armSortWatchdog() {
        cancelSortWatchdog();
        sortWatchdog = new Runnable() {
            @Override
            public void run() {
                sortWatchdog = null;
                if (!alive() || !isLoading()) return;
                LOG.e("echo--getSort-watchdog--force-end");
                showSuccess();
                SourceBean hb = ApiConfig.get().getHomeSourceBean();
                if (hb != null && !homeRecSelfHealFired) {
                    homeRecSelfHealFired = true;
                    sourceViewModel.getSort(hb.getKey());
                }
            }
        };
        try {
            mHandler.postDelayed(sortWatchdog, 12000);
        } catch (Throwable ignored) {
        }
    }

    private void cancelSortWatchdog() {
        if (sortWatchdog != null) {
            try {
                mHandler.removeCallbacks(sortWatchdog);
            } catch (Throwable ignored) {
            }
            sortWatchdog = null;
        }
    }
    // 原地切站前记录的上一站点 key(切站失败回滚时用)
    private String homeSwitchPrevKey = null;

    // 小贾影视仓 v15.4: Activity 存活防护 —— 切线路重启后, 旧实例异步回调(最长10s超时窗口)不得再碰已销毁的 UI
    // (TipDialog.show 于 finishing Activity 会抛 BadTokenException, 是切换偶发闪退来源之一)
    private boolean alive() {
        return !isFinishing() && !isDestroyed();
    }

    private void postIfAlive(Runnable r) {
        mHandler.post(() -> {
            if (alive()) r.run();
        });
    }

    private void postDelayedIfAlive(Runnable r, long delay) {
        mHandler.postDelayed(() -> {
            if (alive()) r.run();
        }, delay);
    }

    // takagen99 : Switch to show / hide source title
    boolean HomeShow = Hawk.get(HawkConfig.HOME_SHOW_SOURCE, false);

    // takagen99 : Check if network is available
    boolean isNetworkAvailable() {
        ConnectivityManager cm
                = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo activeNetworkInfo = cm.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnectedOrConnecting();
    }

    private void initData() {
        // 小贾影视仓 v15.4: 移除 WiFi/样式图标初始化(对应控件已从顶栏删除, 样式开关在信号源面板)

        mGridView.requestFocus();

        if (dataInitOk && jarInitOk) {
            // 小贾影视仓 v15.15: 判空防 NPE(配置未就绪时 getHomeSourceBean 可能为 null)
            SourceBean hsBean = ApiConfig.get().getHomeSourceBean();
            if (hsBean != null) {
                // 小贾影视仓 v17: 先后台预热 extend(type0/1/4 线路), 避免 getSort 在主线程同步等它
                sourceViewModel.prefetchExt(hsBean);
                sourceViewModel.getSort(hsBean.getKey());
                // 小贾影视仓 v18: 起"结果不到场"守卫, 防止线程池饥饿让首页永远停在 loading
                armSortWatchdog();
            }
            if (hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                LOG.e("有");
            } else {
                LOG.e("无");
            }
            if (Hawk.get(HawkConfig.HOME_DEFAULT_SHOW, false)) {
                jumpActivity(LivePlayActivity.class);
            }         
            return;
        }
        tvNameAnimation();
        showLoading();
        if (dataInitOk && !jarInitOk) {
            if (!ApiConfig.get().getSpider().isEmpty()) {
                ApiConfig.get().loadJar(useCacheConfig, ApiConfig.get().getSpider(), new ApiConfig.LoadConfigCallback() {
                    @Override
                    public void success() {
                        jarInitOk = true;
                        postDelayedIfAlive(new Runnable() {
                            @Override
                            public void run() {
                                if (!useCacheConfig) {
                                    Toast.makeText(HomeActivity.this, getString(R.string.hm_ok), Toast.LENGTH_SHORT).show();
                                }
                                initData();
                            }
                        }, 50);
                    }

                    @Override
                    public void retry() {

                    }

                    @Override
                    public void error(String msg) {
                        jarInitOk = true;
                        dataInitOk = true;
                        postDelayedIfAlive(new Runnable() {
                            @Override
                            public void run() {
                                // 小贾影视仓 v15.15: 内置包(去加固 jar)加载失败 -> 自动降级到默认在线线路并重启首页,
                                // 避免用户卡在"内置线路加载不出来又不自愈"的死状态。
                                String curApi = Hawk.get(HawkConfig.API_URL, getString(R.string.app_source));
                                if (curApi != null && curApi.startsWith("clan://")) {
                                    Toast.makeText(HomeActivity.this, "内置包加载失败，已自动切到默认线路", Toast.LENGTH_LONG).show();
                                    Hawk.put(HawkConfig.API_URL, getString(R.string.app_source));
                                    restartHome(true);
                                    return;
                                }
                                if ("".equals(msg))
                                    Toast.makeText(HomeActivity.this, getString(R.string.hm_notok), Toast.LENGTH_SHORT).show();
                                else
                                    Toast.makeText(HomeActivity.this, msg, Toast.LENGTH_SHORT).show();
                                initData();
                            }
                        },50);
                    }
                });
            }
            return;
        }
        ApiConfig.get().loadConfig(useCacheConfig, new ApiConfig.LoadConfigCallback() {
            TipDialog dialog = null;

            @Override
            public void retry() {
                postIfAlive(new Runnable() {
                    @Override
                    public void run() {
                        initData();
                    }
                });
            }

            @Override
            public void success() {
                dataInitOk = true;
                if (ApiConfig.get().getSpider().isEmpty()) {
                    jarInitOk = true;
                }
                postDelayedIfAlive(new Runnable() {
                    @Override
                    public void run() {
                        initData();
                    }
                }, 50);
            }

            @Override
            public void error(String msg) {
                if (msg.equalsIgnoreCase("-1")) {
                    postIfAlive(new Runnable() {
                        @Override
                        public void run() {
                            dataInitOk = true;
                            jarInitOk = true;
                            initData();
                        }
                    });
                    return;
                }
                postIfAlive(new Runnable() {
                    @Override
                    public void run() {
                        if (dialog == null)
                            dialog = new TipDialog(HomeActivity.this, msg, getString(R.string.hm_retry), getString(R.string.hm_cancel), new TipDialog.OnListener() {
                                @Override
                                public void left() {
                                    postIfAlive(new Runnable() {
                                        @Override
                                        public void run() {
                                            initData();
                                            dialog.hide();
                                        }
                                    });
                                }

                                @Override
                                public void right() {
                                    dataInitOk = true;
                                    jarInitOk = true;
                                    postIfAlive(new Runnable() {
                                        @Override
                                        public void run() {
                                            initData();
                                            dialog.hide();
                                        }
                                    });
                                }

                                @Override
                                public void cancel() {
                                    dataInitOk = true;
                                    jarInitOk = true;
                                    postIfAlive(new Runnable() {
                                        @Override
                                        public void run() {
                                            initData();
                                            dialog.hide();
                                        }
                                    });
                                }
                            });
                        if (!dialog.isShowing())
                            dialog.show();
                    }
                });
            }
        }, this);
    }

    // 小贾影视仓 v15.4: 原地销毁当前全部 fragment 页并解绑 ViewPager —— 根治旧 HomePageAdapter
    // (FragmentPagerAdapter.destroyItem 只 hide 不 remove) 反复 initViewPager 造成的
    // "页数翻倍 / FragmentManager 滞留 / tag 冲突"。先 setAdapter(null) 断开旧页回调, 再逐个真 remove。
    private void clearPagesInPlace() {
        try {
            if (mViewPager != null && pageAdapter != null) {
                mViewPager.setAdapter(null);
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        pageAdapter = null;
        if (fragments.isEmpty()) return;
        try {
            FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
            for (BaseLazyFragment f : fragments) {
                if (f != null) {
                    try {
                        transaction.remove(f);
                    } catch (Throwable ignored) {
                    }
                }
            }
            transaction.commitAllowingStateLoss();
            fragments.clear();
            try {
                getSupportFragmentManager().executePendingTransactions();
            } catch (Throwable ignored) {
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    private void initViewPager(AbsSortXml absXml) {
        // 小贾影视仓 v15.4: 每次重建前先原地销毁旧页(真 remove), 根治"页数翻倍/tag 冲突"
        clearPagesInPlace();
        if (sortAdapter.getData().size() > 0) {
            for (MovieSort.SortData data : sortAdapter.getData()) {
                if (data.id.equals("my0")) {
                    if (Hawk.get(HawkConfig.HOME_REC, 0) == 1 && absXml != null && absXml.videoList != null && absXml.videoList.size() > 0) {
                        fragments.add(UserFragment.newInstance(absXml.videoList));
                    } else {
                        fragments.add(UserFragment.newInstance(null));
                    }
                } else {
                    fragments.add(GridFragment.newInstance(data));
                }
            }
            pageAdapter = new HomePageAdapter(getSupportFragmentManager(), fragments);
            try {
                Field field = ViewPager.class.getDeclaredField("mScroller");
                field.setAccessible(true);
                FixedSpeedScroller scroller = new FixedSpeedScroller(mContext, new AccelerateInterpolator());
                field.set(mViewPager, scroller);
                scroller.setmDuration(300);
            } catch (Exception e) {
            }
            mViewPager.setPageTransformer(true, new DefaultTransformer());
            mViewPager.setAdapter(pageAdapter);
            mViewPager.setCurrentItem(currentSelected, false);
        }
    }

    @Override
    public void onBackPressed() {
        //打断加载
        if(isLoading()){
            refreshEmpty();
            return;
        }
        // 如果处于 VOD 删除模式，则退出该模式并刷新界面
        if (HawkConfig.hotVodDelete) {
            HawkConfig.hotVodDelete = false;
            // v15.6.1: homeHotVodAdapter 是 UserFragment 的静态字段, 切线路重建 fragments 期间可能为 null
            if (UserFragment.homeHotVodAdapter != null) {
                UserFragment.homeHotVodAdapter.notifyDataSetChanged();
            }
            return;
        }

        // 检查 fragments 状态
        if (this.fragments.size() <= 0 || this.sortFocused >= this.fragments.size() || this.sortFocused < 0) {
            doExit();
            return;
        }

        BaseLazyFragment baseLazyFragment = this.fragments.get(this.sortFocused);
        if (baseLazyFragment instanceof GridFragment) {
            GridFragment grid = (GridFragment) baseLazyFragment;
            // 如果当前 Fragment 能恢复之前保存的 UI 状态，则直接返回
            if (grid.restoreView()) {
                return;
            }
            // 如果 sortFocusView 存在且没有获取焦点，则请求焦点
            if (this.sortFocusView != null && !this.sortFocusView.isFocused()) {
                this.sortFocusView.requestFocus();
            }
            // 如果当前不是第一个界面，则将列表设置到第一项
            else if (this.sortFocused != 0) {
                this.mGridView.setSelection(0);
            } else {
                doExit();
            }
        } else if (baseLazyFragment instanceof UserFragment) {
            // v15.6 横屏海报墙首页: 焦点在页面内部(海报网格/快捷入口行)时, 先回滚网格并把焦点归还左侧分类导航;
            // 焦点已在分类导航: 不在首页tab则切回首页, 已在首页则走双击退出。
            if (this.sortFocusView != null && !this.sortFocusView.isFocused()) {
                try {
                    if (UserFragment.tvHotListForGrid != null && UserFragment.tvHotListForGrid.canScrollVertically(-1)) {
                        UserFragment.tvHotListForGrid.scrollToPosition(0);
                    }
                } catch (Throwable ignored) {
                }
                this.sortFocusView.requestFocus();
            } else if (this.sortFocused != 0) {
                this.mGridView.setSelection(0);
            } else {
                doExit();
            }
        } else {
            doExit();
        }
    }

    private void doExit() {
        // 如果两次返回间隔小于 2000 毫秒，则退出应用
        if (System.currentTimeMillis() - mExitTime < 2000) {
            AppManager.getInstance().finishAllActivity();
            EventBus.getDefault().unregister(this);
            ControlManager.get().stopServer();
            finish();
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(0);
        } else {
            // 否则仅提示用户，再按一次退出应用
            mExitTime = System.currentTimeMillis();
            Toast.makeText(mContext, getString(R.string.hm_exit), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // 小贾影视仓: 标题显示当前线路名(红底白字)
        tvName.setText(getString(R.string.app_name) + " · " + getLineName(Hawk.get(HawkConfig.API_URL, getString(R.string.app_source))));
        tvName.clearAnimation();

        // takagen99: Icon Placement
        if (Hawk.get(HawkConfig.HOME_SEARCH_POSITION, true)) {
            tvFind.setVisibility(View.VISIBLE);
        } else {
            tvFind.setVisibility(View.GONE);
        }
        if (Hawk.get(HawkConfig.HOME_MENU_POSITION, true)) {
            tvMenu.setVisibility(View.VISIBLE);
        } else {
            tvMenu.setVisibility(View.GONE);
        }

        // v15.4.3: 傻瓜化崩溃上报 —— 上次闪退过则自动弹窗(摘要+一键复制/分享), 免去浏览器抓日志
        checkCrashReport();
        // v15.15: 启动哨兵 —— 兜住 native 崩溃(Java 抓不到、无日志无弹窗)导致的"开一次崩一次"
        checkBootSentinel();
    }

    // ===== 小贾影视仓 v15.15: 启动哨兵 —— 专治 native 崩溃"开一次崩一次" =====
    // 背景: 加固 .so 在 Android 15+ 16KB 内存页设备上 dlopen 会原生崩溃, Java 层完全抓不到(不写 xj_crash.log、不弹窗),
    // 用户重启多少次都还是崩。这里用"启动未完成标记"兜底: 标记还在 => 上次启动没走完(崩了/被杀) => 计数+1;
    // 连续 2 次且当前是内置 clan 线路 => 自动切到默认在线线路; 连续 3 次(任意线路) => 也切回默认线路。首页数据到位后清标记。
    private static final String BOOT_PENDING = "xj_boot_pending";
    private static final String BOOT_FAIL_CNT = "xj_boot_fail_cnt";

    private int readBootFail() {
        try {
            java.io.File f = new java.io.File(getFilesDir(), BOOT_FAIL_CNT);
            if (!f.exists()) return 0;
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            byte[] b = new byte[16];
            int n = fis.read(b);
            fis.close();
            return n > 0 ? Integer.parseInt(new String(b, 0, n, "UTF-8").trim()) : 0;
        } catch (Throwable th) {
            return 0;
        }
    }

    private void writeBootFail(int v) {
        try {
            java.io.FileOutputStream fos = new java.io.FileOutputStream(new java.io.File(getFilesDir(), BOOT_FAIL_CNT), false);
            fos.write(String.valueOf(v).getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) {
        }
    }

    // 小贾影视仓 v22.1: 用「进程 pid」区分两种"启动未完成" ——
    //   ① 上次进程真的死了(native 崩/被系统杀)  → 该计数;
    //   ② 同一进程内的 Activity 重启(切线路/长按刷新走 restartHome 新建 HomeActivity) → 不该计数。
    // 旧实现只要 xj_boot_pending 存在就 fail+1, 而切线路恰恰会重建 Activity 并重进 onCreate;
    // 于是"切到一条慢线路 → 首页数据还没到 → 用户等不及又切一次"会被记成一次"启动异常",
    // 连续 3 次就强制回退默认线路 —— 正是用户看到的「个别线路加载慢, 还提示检测到上次线路启动异常」。
    private void checkBootSentinel() {
        try {
            java.io.File pending = new java.io.File(getFilesDir(), BOOT_PENDING);
            int myPid = android.os.Process.myPid();
            String lastPid = "";
            if (pending.exists()) {
                try {
                    byte[] b = new byte[32];
                    java.io.FileInputStream fis = new java.io.FileInputStream(pending);
                    int n = fis.read(b);
                    fis.close();
                    if (n > 0) lastPid = new String(b, 0, n, "UTF-8").trim();
                } catch (Throwable ignored) {
                }
            }
            int fail;
            if (!pending.exists() || lastPid.isEmpty()) {
                // 首次启动, 或旧版本遗留的"空标记"文件(没有 pid 信息) —— 一律当首次, 不冤枉用户
                fail = 0;
            } else if (lastPid.equals(String.valueOf(myPid))) {
                // 同一个进程里又新建了一次 HomeActivity = 切线路/刷新, 不是崩溃
                fail = readBootFail();
            } else {
                fail = readBootFail() + 1;
            }
            writeBootFail(fail);
            // 标记内容写入当前 pid(同时完成"创建标记文件")
            java.io.FileOutputStream fos = new java.io.FileOutputStream(pending, false);
            fos.write(String.valueOf(myPid).getBytes("UTF-8"));
            fos.flush();
            fos.close();
            String cur = Hawk.get(HawkConfig.API_URL, getString(R.string.app_source));
            boolean isBuiltin = cur != null && cur.startsWith("clan://");
            if ((fail >= 2 && isBuiltin) || fail >= 3) {
                Hawk.put(HawkConfig.API_URL, getString(R.string.app_source));
                writeBootFail(0);
                pending.delete();
                Toast.makeText(this, "检测到上次启动异常，已自动切到默认线路", Toast.LENGTH_LONG).show();
            }
        } catch (Throwable ignored) {
        }
    }

    private void markBootOk() {
        try {
            java.io.File pending = new java.io.File(getFilesDir(), BOOT_PENDING);
            if (pending.exists()) pending.delete();
            writeBootFail(0);
        } catch (Throwable ignored) {
        }
    }

    // v15.4.3: 检测上次崩溃(xj_crash_last.txt), 弹窗让用户一键复制/分享日志给开发者
    private boolean crashReportShown = false;

    private void checkCrashReport() {
        if (crashReportShown) return;
        final java.io.File cf = new java.io.File(getFilesDir(), "xj_crash_last.txt");
        if (!cf.exists()) return;
        crashReportShown = true;
        final String fullLog;
        try {
            java.io.FileInputStream fis = new java.io.FileInputStream(cf);
            byte[] buf = new byte[(int) Math.min(cf.length(), 512 * 1024)];
            int n = fis.read(buf);
            fis.close();
            fullLog = n > 0 ? new String(buf, 0, n, "UTF-8") : "(empty)";
        } catch (Throwable ignored) {
            return;
        }
        // 摘要 = 非空、非 "at "、非 "==" 开头、含异常关键字或冒号的短行; 兜底截前 180 字符
        String summary = "未知";
        for (String line : fullLog.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("at ") || t.startsWith("==") || t.startsWith("..."))
                continue;
            if (t.contains("Exception") || t.contains("Error") || t.contains(":")) {
                summary = t.length() > 220 ? t.substring(0, 220) : t;
                break;
            }
        }
        final String fl = fullLog;
        try {
            new AlertDialog.Builder(this)
                    .setTitle("上次运行闪退了一次")
                    .setMessage("原因大概是：\n" + summary + "\n\n帮我反馈给开发者（两步）：\n① 点【分享日志】→ 选微信「文件传输助手」发送；\n② 或点【复制日志】→ 微信里粘贴发送。\n\n手机上操作就行，不用电脑")
                    .setPositiveButton("分享日志", (d, w) -> {
                        android.content.Intent si = new android.content.Intent(android.content.Intent.ACTION_SEND);
                        si.setType("text/plain");
                        si.putExtra(android.content.Intent.EXTRA_TEXT, "【小贾影视仓崩溃日志】\n" + fl);
                        try {
                            startActivity(android.content.Intent.createChooser(si, "把日志发到微信文件传输助手"));
                        } catch (Throwable th) {
                            Toast.makeText(mContext, "没有可用的分享应用", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("复制日志", (d, w) -> {
                        try {
                            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("xj_crash", fl));
                            Toast.makeText(mContext, "已复制！去微信文件传输助手粘贴发我", Toast.LENGTH_LONG).show();
                        } catch (Throwable th) {
                            Toast.makeText(mContext, "复制失败，长按手动复制也可", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNeutralButton("知道了", null)
                    .setOnDismissListener(d -> {
                        try {
                            cf.delete();
                        } catch (Throwable ignored) {
                        }
                    })
                    .show();
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacksAndMessages(null);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void refresh(RefreshEvent event) {
        if (event.type == RefreshEvent.TYPE_PUSH_URL) {
            if (ApiConfig.get().getSource("push_agent") != null) {
                Intent newIntent = new Intent(mContext, DetailActivity.class);
                newIntent.putExtra("id", (String) event.obj);
                newIntent.putExtra("sourceKey", "push_agent");
                newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                HomeActivity.this.startActivity(newIntent);
            }
        } else if (event.type == RefreshEvent.TYPE_FILTER_CHANGE) {
            if (currentView != null) {
//                showFilterIcon((int) event.obj);
            }
        }
    }

    private void showFilterIcon(int count) {
        boolean activated = count > 0;
        currentView.findViewById(R.id.tvFilter).setVisibility(View.VISIBLE);
        ImageView imgView = currentView.findViewById(R.id.tvFilter);
        imgView.setColorFilter(activated ? this.getThemeColor() : Color.WHITE);
    }

    private final Runnable mDataRunnable = new Runnable() {
        @Override
        public void run() {
            if (sortChange) {
                sortChange = false;
                if (sortFocused != currentSelected) {
                    currentSelected = sortFocused;
                    mViewPager.setCurrentItem(sortFocused, false);
                    changeTop(sortFocused != 0);
                }
            }
        }
    };

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (topHide < 0)
            return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
                showSiteSwitch();
            }
//            if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN) {
//                if () {
//
//                }
//            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {

        }
        return super.dispatchKeyEvent(event);
    }

    byte topHide = 0;

    private void changeTop(boolean hide) {
        ViewObj viewObj = new ViewObj(topLayout, (ViewGroup.MarginLayoutParams) topLayout.getLayoutParams());
        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.addListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animation) {

            }

            @Override
            public void onAnimationEnd(Animator animation) {
                topHide = (byte) (hide ? 1 : 0);
            }

            @Override
            public void onAnimationCancel(Animator animation) {

            }

            @Override
            public void onAnimationRepeat(Animator animation) {

            }
        });
        // Hide Top =======================================================
        if (hide && topHide == 0) {
            animatorSet.playTogether(ObjectAnimator.ofObject(viewObj, "marginTop", new IntEvaluator(),
                            Integer.valueOf(AutoSizeUtils.mm2px(this.mContext, 20.0f)),
                            Integer.valueOf(AutoSizeUtils.mm2px(this.mContext, 0.0f))),
                    ObjectAnimator.ofObject(viewObj, "height", new IntEvaluator(),
                            Integer.valueOf(AutoSizeUtils.mm2px(this.mContext, 50.0f)),
                            Integer.valueOf(AutoSizeUtils.mm2px(this.mContext, 1.0f))),
                    ObjectAnimator.ofFloat(this.topLayout, "alpha", 1.0f, 0.0f));
            animatorSet.setDuration(250);
            animatorSet.start();
            tvName.setFocusable(false);
            tvFind.setFocusable(false);
            tvMenu.setFocusable(false);
            tvLive.setFocusable(false);
            return;
        }
        // Show Top =======================================================
        if (!hide && topHide == 1) {
            animatorSet.playTogether(ObjectAnimator.ofObject(viewObj, "marginTop", new IntEvaluator(),
                            Integer.valueOf(AutoSizeUtils.mm2px(this.mContext, 0.0f)),
                            Integer.valueOf(AutoSizeUtils.mm2px(this.mContext, 20.0f))),
                    ObjectAnimator.ofObject(viewObj, "height", new IntEvaluator(),
                            Integer.valueOf(AutoSizeUtils.mm2px(this.mContext, 1.0f)),
                            Integer.valueOf(AutoSizeUtils.mm2px(this.mContext, 50.0f))),
                    ObjectAnimator.ofFloat(this.topLayout, "alpha", 0.0f, 1.0f));
            animatorSet.setDuration(250);
            animatorSet.start();
            tvName.setFocusable(true);
            tvFind.setFocusable(true);
            tvMenu.setFocusable(true);
            tvLive.setFocusable(true);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 小贾影视仓 v15.3: 修复"切换线路必闪退" —— 原代码在 onDestroy 里调 appExit(0)(killProcess 杀进程)
        // 和 stopServer()(停本地HTTP服务)。切换线路走 restartHome(finish 旧 Activity) 时, 这两行会把整个进程杀掉
        // 或把 clan:// 依赖的本地服务停掉 → 一切换就闪退、重进(冷启动)才好。
        // 真正退出由 doExit() 自己 finishAllActivity+killProcess+System.exit, 不依赖 onDestroy。
        EventBus.getDefault().unregister(this);
    }

    // 小贾影视仓 v15.4: 统一「信号源」面板 —— 左列线路(配置级) / 右列站点(SourceBean)。
    // 站点点击走原地切站(switchHomeSourceInPlace, 不重启 Activity); 线路点击=持久化+整页重载。
    // 入口: tvName 胶囊单击 / MENU 键 / 「我的」页点击; 长按 tvName 仍为强制刷新(reloadHomeFresh)。
    void showSiteSwitch() {
        java.util.LinkedHashMap<String, String> lineMap = buildLineCandidates();
        if (lineMap.isEmpty()) {
            Toast.makeText(this, "暂无线路, 请先点\"线路配置\"添加", Toast.LENGTH_SHORT).show();
            return;
        }
        List<SourcePanelDialog.LineItem> lineItems = new ArrayList<>();
        int lineSelect = 0;
        String curLine = Hawk.get(HawkConfig.API_URL, getString(R.string.app_source));
        int idx = 0;
        for (java.util.Map.Entry<String, String> e : lineMap.entrySet()) {
            lineItems.add(new SourcePanelDialog.LineItem(e.getKey(), e.getValue()));
            if (e.getValue().equals(curLine)) lineSelect = idx;
            idx++;
        }
        // 小贾影视仓 v22.1: 用户自己加载的「本地 .py」站点排在宫格最前 ——
        // 之前它们跟线路自带站点混在一起排在最后, 用户加载完在面板里根本找不到, 误以为"没加载成功"。
        List<SourceBean> sites = new ArrayList<>();
        List<SourceBean> localPySites = new ArrayList<>();
        for (SourceBean sb : ApiConfig.get().getSourceBeanList()) {
            if (sb.getHide() != 0) continue;
            if (sb.getKey() != null && sb.getKey().startsWith("py_local_")) localPySites.add(sb);
            else sites.add(sb);
        }
        sites.addAll(0, localPySites);
        if (sites.isEmpty()) {
            Toast.makeText(this, "当前线路暂无站点, 请先点\"线路配置\"确认", Toast.LENGTH_SHORT).show();
            return;
        }
        final SourcePanelDialog panel = new SourcePanelDialog(this, lineItems, lineSelect, sites,
                Math.max(0, sites.indexOf(ApiConfig.get().getHomeSourceBean())), homeStyleText());
        panel.setOnSourcePanelAction(new SourcePanelDialog.OnSourcePanelAction() {
            @Override
            public void onSwitchLine(SourcePanelDialog.LineItem item) {
                applyLineSelection(item.name, item.url);
            }

            @Override
            public void onSwitchSite(SourceBean site) {
                switchHomeSourceInPlace(site);
            }

            @Override
            public void onOpenConfig() {
                openConfigManager();
            }

            @Override
            public void onToggleHomeStyle() {
                toggleHomeStyle();
            }

            @Override
            public void onPickLocalPy() {
                pickLocalPy();
            }
        });
        panel.show();
        // 焦点落到右列站点列表(高频操作)
        if (panel.getWindow() != null) {
            panel.getWindow().getDecorView().post(new Runnable() {
                @Override
                public void run() {
                    TvRecyclerView siteList = panel.findViewById(R.id.panelSiteList);
                    if (siteList != null) siteList.requestFocus();
                }
            });
        }
    }

    // 小贾影视仓 v15.4: 候选线路(预置+历史+当前), 返回 显示名->地址(按地址去重, 保留插入序)
    java.util.LinkedHashMap<String, String> buildLineCandidates() {
        java.util.LinkedHashMap<String, String> lines = new java.util.LinkedHashMap<>();
        // 预置线路(名称, 地址) —— v15 筛选(2026-09 实测)
        // 肥猫·net: 2423 AES 加密配置(引擎原生解密), 40站含豆瓣+lives, 实测可用(肥猫.com 主站已挂)
        // 王二小: 官网域名已改个人落地页/JS单源(basic auth), 真实配置=kstore newwex/wex/aiwex(防封)
        // 饭太硬 art/net 双域名现指向同一 JPEG 图片配置(v11 图片解码器可解)
        // 剔除: 潇洒(404)、小不点(HTML)、JK·catvod(HTML)、魔力云播cat(JS单源无法解析)
        String[][] presetLines = new String[][]{
                {"itv666·嗷呜(默认)", "http://itv666.cc/aowu/config.webp"},
                // 小贾影视仓 v21: 「内置·肥猫全能包·120站」整条下线 —— 内置加固包(guard_v7/v8.so)在部分机型上
                // 点击即 native 崩(无弹窗、无 xj_crash.log), 且所有公共接口的 spider 都是同款加固,
                // 内置=定时炸弹。这正是 AVBox 的精髓所在: 它的 assets 里根本不内置任何加固包。
                // (如需再启用, 只能改为"引导用户自配无需加固的 JS/Python 线路"。)
                {"肥猫·net", "http://肥猫.net/tv"},
                {"饭太硬·主", "http://www.饭太硬.art/tv"},
                {"kstore·88站", "https://9280.kstore.vip/newwex.json"},
                {"王二小·wex防封", "https://9280.kstore.vip/wex.json"},
                {"安卓三代·aiwex", "https://9280.kstore.vip/aiwex.json"},
                {"张群·19站", "https://zhangqun1818.serv00.net/zq/api.json"},
                {"日后", "http://rihou.cc:88/demo.php"},
                {"饭太硬·镜像", "http://www.饭太硬.net/tv"},
                {"瓜子·HGYX", "https://api.hgyx.vip/hgyx.json"},
                // 小贾影视仓 v19: 用户提供的新接口(2026-10-07 实测可用)
                {"摸鱼儿·97站", "http://我不是.摸鱼儿.top"},
                {"嗷呜呜·78站", "http://www.英格里希嗷呜.top/tv"},
                {"挺好的·46站", "https://ztha.top/TVBox/thdjk.json"},
                {"小白马·62站", "https://szyyds.cn/tv/x.json"},
                {"菜泥丝·46站", "https://tv.xn--yhqu5zs87a.top"}
        };
        for (String[] p : presetLines) {
            if (!lines.containsValue(p[1])) lines.put(p[0], p[1]);
        }
        ArrayList<String> history = Hawk.get(HawkConfig.API_HISTORY, new ArrayList<String>());
        for (String h : history) {          // 历史/手动添加的线路(始终保留)
            if (!lines.containsValue(h)) lines.put(h, h);
        }
        String current = Hawk.get(HawkConfig.API_URL, getString(R.string.app_source));
        if (current != null && !current.isEmpty() && !lines.containsValue(current)) {
            lines.put(getLineName(current), current);
        }
        return lines;
    }

    // 小贾影视仓 v15.4: 应用线路选择(预置/历史) —— 持久化历史 + API_URL 后整页重载新配置
    void applyLineSelection(final String name, final String url) {
        if (url == null || url.isEmpty()) return;
        String current = Hawk.get(HawkConfig.API_URL, getString(R.string.app_source));
        if (url.equals(current)) return;
        ArrayList<String> hist = Hawk.get(HawkConfig.API_HISTORY, new ArrayList<String>());
        if (!hist.contains(url)) hist.add(0, url);
        if (hist.size() > 20) hist.remove(20);
        Hawk.put(HawkConfig.API_HISTORY, hist);
        Hawk.put(HawkConfig.API_URL, url);
        // 小贾影视仓 v22.1: 用户主动切线路 => 立刻清掉启动哨兵标记(计数 + 待完成标记)。
        // 切线路会重建 HomeActivity, 新线路若加载慢, 用户等不及往往会再切一次;
        // 旧逻辑会把这串正常操作累计成"启动异常"并在第 3 次强制回退默认线路。主动切换不算异常。
        try {
            writeBootFail(0);
            new java.io.File(getFilesDir(), BOOT_PENDING).delete();
        } catch (Throwable ignored) {
        }
        Toast.makeText(this, "已切换到: " + name, Toast.LENGTH_SHORT).show();
        reloadHome();
    }

    // 小贾影视仓 v15.5: 首页已升级为「大图焦点」模式(B方案), 原 宫格/横排 样式开关废除 —— 点击仅提示
    void toggleHomeStyle() {
        Toast.makeText(this, getString(R.string.hm_style_fixed), Toast.LENGTH_SHORT).show();
    }

    private String homeStyleText() {
        return getString(R.string.hm_style_focus);
    }

    // 小贾影视仓 v15.4: 线路配置(ApiDialog) —— 原顶栏 tvApi, 迁入信号源面板
    void openConfigManager() {
        try {
            ApiDialog dialog = new ApiDialog(HomeActivity.this);
            EventBus.getDefault().register(dialog);
            dialog.setOnListener(new ApiDialog.OnListener() {
                @Override
                public void onchange(String api) {
                    Hawk.put(HawkConfig.API_URL, api);
                    // 小贾影视仓: 不再清空 HOME_API(让"豆瓣"推荐跨线路固定), parseJson 兜底选第一个非 meta 内容站
                }
            });
            dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface d) {
                    EventBus.getDefault().unregister(d);
                    reloadHome();
                }
            });
            dialog.show();
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    // 小贾影视仓 v15.4: 原地切站主流程。
    // 语义: 站点(SourceBean)级切换 => 写 Hawk 持久化 => 取消上一站点可能仍在途的 HTTP sort 请求
    // (type0/1/4 的 OkGo tag = sourceKey+"_sort") => homeSortGuard=目标key => 直接 getSort(目标key)
    // => sortResult observer 守卫通过后 原地 clearPages + 重建分类/ViewPager。同站点击直接忽略,
    // 切换中二次触发给 Toast 防抖。失败路径在 observer 内回滚原站 + restartHome 兜底。
    void switchHomeSourceInPlace(final SourceBean target) {
        if (target == null) return;
        if (inPlaceSwitching) {
            Toast.makeText(this, "正在切换站点, 请稍候…", Toast.LENGTH_SHORT).show();
            return;
        }
        SourceBean current = ApiConfig.get().getHomeSourceBean();
        String oldKey = current == null ? "" : current.getKey();
        if (oldKey.equals(target.getKey())) {
            Toast.makeText(this, "当前已是「" + target.getName() + "」", Toast.LENGTH_SHORT).show();
            return;
        }
        // 提前取消上一站点仍在途的 HTTP 分类请求, 收窄"旧结果串台"窗口
        try {
            OkGo.getInstance().cancelTag(oldKey + "_sort");
        } catch (Throwable ignored) {
        }
        // 记录上一站 key(失败回滚用), 再持久化目标站点并发出原地加载
        homeSwitchPrevKey = oldKey;
        ApiConfig.get().setSourceBean(target);
        homeSortGuard = target.getKey();
        inPlaceSwitching = true;
        currentSelected = 0;
        sortFocused = 0;
        if (mGridView != null) mGridView.setSelection(0);
        tvName.clearAnimation();
        tvName.setText(getString(R.string.app_name) + " · " + target.getName() + "(切换中…)");
        showLoading();
        // 小贾影视仓 v17: 切线路时先预热目标站的 extend, 首页分类不必在主线程等它
        sourceViewModel.prefetchExt(target);
        sourceViewModel.getSort(target.getKey());
    }

    // 小贾影视仓 v15.1: 切线路/换源/加载本地接口 —— 统一"重启首页 Activity"(takagen99 原版模式)。
    // 为什么不再 in-place 重载: fragments 是成员 List 且只在 initViewPager 里 add、从不清理,
    // HomePageAdapter(FragmentPagerAdapter) 的 destroyItem 只 hide 不 remove → 旧 tab 全部滞留 FragmentManager;
    // 原地二次 initViewPager 会再 append 一套新 fragment → 页数翻倍、tag 冲突、状态错乱 → 闪退/卡死。
    // 重启后 Activity/Fragment 全新建造, 从机制上杜绝该问题。useCache=true 时配置走磁盘缓存秒读、
    // main jar 走 md5 缓存直接 dex, 切换体感仍接近秒切。
    void reloadHome() {
        restartHome(true);
    }
    // 小贾影视仓: 强制从网络重拉(忽略磁盘缓存), 用于长按标题"刷新"
    void reloadHomeFresh() {
        restartHome(false);
    }

    private void restartHome(boolean useCache) {
        try {
            Intent intent = new Intent(this, HomeActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
            Bundle bundle = new Bundle();
            bundle.putBoolean("useCache", useCache);
            intent.putExtras(bundle);
            startActivity(intent);
            finish();
        } catch (Throwable th) {
            th.printStackTrace();
            // 保险兜底: 重启失败则退回原地全量重载
            dataInitOk = false;
            jarInitOk = false;
            initData();
        }
    }

    // 小贾影视仓: 本地接口文件选择结果 —— 复制到私有目录后以 file:// 设为线路(ApiConfig.loadConfigUrl 支持读本地文件)
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // 小贾影视仓 v21: 本地 .py 单源选择结果
        if (requestCode == REQ_PICK_LOCAL_PY) {
            handleLocalPyPicked(resultCode, data);
            return;
        }
        if (requestCode != REQ_PICK_LOCAL_API) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        try {
            java.io.InputStream is = getContentResolver().openInputStream(data.getData());
            if (is == null) {
                Toast.makeText(this, "无法打开所选文件", Toast.LENGTH_SHORT).show();
                return;
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            is.close();
            String path = getFilesDir().getAbsolutePath() + "/local_api.bin";
            java.io.FileOutputStream fos = new java.io.FileOutputStream(path);
            fos.write(bos.toByteArray());
            fos.flush();
            fos.close();
            String api = "file://" + path;
            ArrayList<String> history = Hawk.get(HawkConfig.API_HISTORY, new ArrayList<String>());
            if (!history.contains(api)) history.add(0, api);
            if (history.size() > 20) history.remove(20);
            Hawk.put(HawkConfig.API_HISTORY, history);
            Hawk.put(HawkConfig.API_URL, api);
            Toast.makeText(this, "已加载本地接口文件, 正在刷新…", Toast.LENGTH_SHORT).show();
            reloadHome();
        } catch (Throwable th) {
            th.printStackTrace();
            Toast.makeText(this, "本地文件读取失败: " + th.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // 小贾影视仓 v21 / v23: 选择本地 .py 源(信号源面板「加载本地 .py」触发)。
    // v23 起允许**一次多选** —— 短剧源 + 小说源 + 影视源可以一起加载, 不用一个一个来。
    void pickLocalPy() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "text/x-python", "text/plain", "application/octet-stream"});
            startActivityForResult(i, REQ_PICK_LOCAL_PY);
        } catch (Throwable th) {
            th.printStackTrace();
            Toast.makeText(this, "无法打开文件选择器: " + th.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // 小贾影视仓 v21 / v23: 收下选择的本地 .py 源 —— 复制进 filesDir/plugin/, 记进 Hawk, 注入站点后刷新首页。
    // 之所以"复制"而不是直接引用原路径: SAF 给的 content:// 或外部存储路径本进程无权长期直读;
    // 落到私有目录后由本地 HTTP 服务(/file/plugin/xxx.py)稳定供给 pyLoader。
    // v23: 支持**一次多选**(getClipData), 单选仍走 getData() —— 短剧源/小说源/影视源可以一起加载。
    void handleLocalPyPicked(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null) return;
        try {
            // 1) 收集本次选中的全部 uri(多选 / 单选两条路径)
            ArrayList<android.net.Uri> uris = new ArrayList<>();
            android.content.ClipData clip = data.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    android.net.Uri u = clip.getItemAt(i).getUri();
                    if (u != null) uris.add(u);
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            if (uris.isEmpty()) return;

            java.io.File dir = new java.io.File(getFilesDir(), "plugin");
            if (!dir.exists() && !dir.mkdirs()) {
                Toast.makeText(this, "无法创建插件目录", Toast.LENGTH_SHORT).show();
                return;
            }
            ArrayList<String> list = Hawk.get(HawkConfig.LOCAL_PY_LIST, new ArrayList<String>());
            if (list == null) list = new ArrayList<>();

            int ok = 0;
            StringBuilder loaded = new StringBuilder();
            for (int i = 0; i < uris.size(); i++) {
                try {
                    android.net.Uri uri = uris.get(i);
                    String showName = localPyDisplayName(uri);

                    // 2) 复制到私有目录。文件名统一 local_<时间戳>_<序号>.py ——
                    //    绕开中文/空格在 URL 里被截断的老坑, 序号保证"同批多选"不会撞名。
                    java.io.InputStream is = getContentResolver().openInputStream(uri);
                    if (is == null) continue;
                    String fileName = "local_" + System.currentTimeMillis() + "_" + i + ".py";
                    java.io.File out = new java.io.File(dir, fileName);
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                    fos.flush();
                    fos.close();
                    is.close();

                    // 3) 持久化(存相对 filesDir 的路径, 由 ApiConfig.injectLocalPySites 拼成本地服务地址)
                    String rel = "plugin/" + fileName;
                    for (int k = list.size() - 1; k >= 0; k--) {
                        String it = list.get(k);
                        if (it != null && it.startsWith(showName + "|")) list.remove(k);
                    }
                    list.add(0, showName + "|" + rel);
                    ok++;
                    if (loaded.length() > 0) loaded.append("、");
                    loaded.append(showName);
                } catch (Throwable th) {
                    th.printStackTrace();
                }
            }
            if (ok == 0) {
                Toast.makeText(this, "没有成功加载任何 .py", Toast.LENGTH_SHORT).show();
                return;
            }
            Hawk.put(HawkConfig.LOCAL_PY_LIST, list);

            // 4) 立即注入并重载首页(配置重解析时会再注入一次, 幂等)
            try {
                ApiConfig.get().injectLocalPySites();
            } catch (Throwable ignored) {
            }
            // 小贾影视仓 v22.1: 说清"加载到哪去了" —— 本地 py 不是一条独立"线路",
            // 而是当前线路下的一个**站点(单源)**; 面板左侧永远是线路, 站点在右侧宫格最前面。
            String head = (ok == 1) ? ("已加载「" + loaded + "」") : ("已加载 " + ok + " 个源: " + loaded);
            Toast.makeText(this, head + "(本地py)：在信号源面板右侧「站点」最前面，或直接用搜索框搜片", Toast.LENGTH_LONG).show();
            reloadHome();
        } catch (Throwable th) {
            th.printStackTrace();
            Toast.makeText(this, "py 加载失败: " + th.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // 小贾影视仓 v23: 从 content uri 取文件显示名(DISPLAY_NAME), 去掉 .py 后缀
    private String localPyDisplayName(android.net.Uri uri) {
        String showName = "local";
        try {
            android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0 && c.moveToFirst()) {
                    String v = c.getString(idx);
                    if (v != null && !v.trim().isEmpty()) showName = v.trim();
                }
                c.close();
            }
        } catch (Throwable ignored) {
        }
        if (showName.toLowerCase().endsWith(".py")) showName = showName.substring(0, showName.length() - 3);
        if (showName.isEmpty()) showName = "local";
        return showName;
    }

    // 小贾影视仓: 根据线路地址返回显示名称
    public static String getLineName(String url) {
        if (url == null || url.isEmpty()) return "";
        String[][] presetLines = new String[][]{
                {"itv666·嗷呜(默认)", "http://itv666.cc/aowu/config.webp"},
                // 小贾影视仓 v21: 「内置·肥猫全能包·120站」整条下线 —— 内置加固包(guard_v7/v8.so)在部分机型上
                // 点击即 native 崩(无弹窗、无 xj_crash.log), 且所有公共接口的 spider 都是同款加固,
                // 内置=定时炸弹。这正是 AVBox 的精髓所在: 它的 assets 里根本不内置任何加固包。
                // (如需再启用, 只能改为"引导用户自配无需加固的 JS/Python 线路"。)
                {"肥猫·net", "http://肥猫.net/tv"},
                {"饭太硬·主", "http://www.饭太硬.art/tv"},
                {"kstore·88站", "https://9280.kstore.vip/newwex.json"},
                {"王二小·wex防封", "https://9280.kstore.vip/wex.json"},
                {"安卓三代·aiwex", "https://9280.kstore.vip/aiwex.json"},
                {"张群·19站", "https://zhangqun1818.serv00.net/zq/api.json"},
                {"日后", "http://rihou.cc:88/demo.php"},
                {"饭太硬·镜像", "http://www.饭太硬.net/tv"},
                {"瓜子·HGYX", "https://api.hgyx.vip/hgyx.json"},
                // 小贾影视仓 v19: 用户提供的新接口(2026-10-07 实测可用)
                {"摸鱼儿·97站", "http://我不是.摸鱼儿.top"},
                {"嗷呜呜·78站", "http://www.英格里希嗷呜.top/tv"},
                {"挺好的·46站", "https://ztha.top/TVBox/thdjk.json"},
                {"小白马·62站", "https://szyyds.cn/tv/x.json"},
                {"菜泥丝·46站", "https://tv.xn--yhqu5zs87a.top"}
        };
        for (String[] p : presetLines) {
            if (url.equals(p[1])) return p[0];
        }
        try {
            java.net.URI uri = new java.net.URI(url);
            String host = uri.getHost();
            if (host != null && !host.isEmpty()) return host;
        } catch (Exception ignored) {
        }
        return url;
    }

    private void refreshEmpty() {
        skipNextUpdate=true;
        showSuccess();
        // 小贾影视仓 v15.15: 判空防 NPE
        SourceBean emptyBean = ApiConfig.get().getHomeSourceBean();
        sortAdapter.setNewData(DefaultConfig.adjustSort(emptyBean == null ? "" : emptyBean.getKey(), new ArrayList<>(), true));
        initViewPager(null);
        tvName.clearAnimation();
    }

    private void tvNameAnimation()
    {
        AlphaAnimation blinkAnimation = new AlphaAnimation(0.0f, 1.0f);
        blinkAnimation.setDuration(500);
        blinkAnimation.setStartOffset(20);
        blinkAnimation.setRepeatMode(Animation.REVERSE);
        blinkAnimation.setRepeatCount(Animation.INFINITE);
        tvName.startAnimation(blinkAnimation);
    }
//    public void onClick(View v) {
//        FastClickCheckUtil.check(v);
//        if (v.getId() == R.id.tvFind) {
//            jumpActivity(SearchActivity.class);
//        } else if (v.getId() == R.id.tvMenu) {
//            jumpActivity(SettingActivity.class);
//        }
//    }

}
