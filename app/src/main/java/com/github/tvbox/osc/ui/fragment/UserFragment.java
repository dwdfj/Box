package com.github.tvbox.osc.ui.fragment;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cache.RoomDataManger;
import com.github.tvbox.osc.event.ServerEvent;
import com.github.tvbox.osc.ui.activity.*;
import com.github.tvbox.osc.ui.adapter.UserHomeRowAdapter;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.UA;
import com.github.tvbox.osc.util.ImgUtil;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import me.jessyan.autosize.utils.AutoSizeUtils;

/**
 * @author pj567
 * @date :2021/3/9
 * @description: 小贾影视仓 v15.9 —— 首页(my0页签)改为「横屏 · 精选大图 + 网格」(方案B):
 * 1. 背景透明: 露出 BaseActivity 设在 window 上的内置壁纸(底部渐变遮罩保可读性);
 * 2. 顶行: 数据源模式胶囊(豆瓣热播/站点推荐/播放历史) + 计数 + 操作提示;
 * 3. 主体(横屏宽屏): 左侧「精选大图 Banner」(剧照+标题+圆形播放钮+收藏钮, 随右侧网格焦点联动),
 *    右侧「多列海报网格」(默认 4 列)铺开, 信息密度高、无大空白;
 * 4. 底部入口已移除(v23.x 用户反馈): 历史/收藏/推送/网盘改走设置页与搜索页; 直播上移至顶栏。
 * 保留: 三模式切换(长按历史入口或数据源胶囊)、播放历史删除模式(长按网格项)、
 *      v15.6.1 静态字段泄漏修复(onDestroyView 置空)、HomeActivity 通过 tvHotListForGrid 回滚。
 */
public class UserFragment extends BaseLazyFragment implements View.OnClickListener {
    // 小贾影视仓 v23.x: 底部 dock 已移除, 入口改放顶栏(搜索/直播/设置); 历史/收藏等走设置页与搜索页
    public static UserHomeRowAdapter homeHotVodAdapter;   // 网格适配器(静态: HomeActivity 删除模式退出时刷新)
    public static TvRecyclerView tvHotListForGrid;        // 右侧网格容器(静态别名, 兼容 HomeActivity 回滚; 本版指 tvHotGrid)
    private List<Movie.Video> homeSourceRec;              // 站点推荐数据(模式1)
    private Movie.Video currentVideo;                     // 当前焦点片(Banner 与操作钮共用)

    // 顶行 + Banner 控件
    private TextView tvModeTag;
    private TextView tvHotCount;
    private TextView tvHotHint;
    private FrameLayout tvFeaturedBanner;                 // 左侧精选大图 Banner(可点即播)
    private ImageView ivBanner;                           // Banner 剧照
    private TextView tvBannerTitle;                       // Banner 标题
    private TextView tvBannerMeta;                        // Banner 元信息(评分·年份·类型)
    private TextView btnBannerCollect;                    // Banner 收藏钮

    // v27.22: 参考图改版 —— 简介 / 圆点 / 两条横排
    private TextView tvBannerDesc;
    private LinearLayout bannerDots;
    private View secHotHeader, secHot2Header;
    private TvRecyclerView rowHot, rowHot2;
    private UserHomeRowAdapter rowHotAdapter, rowHot2Adapter;
    private final List<Movie.Video> bannerPool = new ArrayList<>();
    // v27.23: 「热门电影 / 热门剧集」两排各自取豆瓣独立榜单 ——
    // 原来两排是把同一个推荐列表切成 0~10 / 10~20, 推荐列表不足 10 条时第二排直接空掉,
    // 而且切出来的"剧集"里混着电影。现在 movie/tv 各请求一次, 两排永远有内容、且类型正确。
    private final List<Movie.Video> movieRow = new ArrayList<>();
    private final List<Movie.Video> tvRow = new ArrayList<>();
    private boolean rowsRequested = false;
    private int bannerIndex = 0;
    private android.os.Handler bannerHandler;

    private int focusPos = 0;

    public static UserFragment newInstance() {
        return new UserFragment();
    }

    public static UserFragment newInstance(List<Movie.Video> recVod) {
        return new UserFragment().setArguments(recVod);
    }

    public UserFragment setArguments(List<Movie.Video> recVod) {
        this.homeSourceRec = recVod;
        return this;
    }

    @Override
    public void onFragmentResume() {
        super.onFragmentResume();
        // v15.6.1: view 被回收后(onDestroyView 已把静态字段置空)本回调仍可能触发,
        // 此时控件引用为 null —— 只走 super(交给懒加载重建), 后续操作全部跳过, 避免 NPE。
        if (tvModeTag == null || tvBannerTitle == null) {
            return;
        }
        // 三模式为「播放历史」时: 由本地历史回填网格
        if (Hawk.get(HawkConfig.HOME_REC, 0) == 2 && homeHotVodAdapter != null) {
            homeHotVodAdapter.setNewData(historyToVideos(RoomDataManger.getAllVodRecord(30)));
        }
        refreshModeTag();
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_user;
    }

    @Override
    protected void init() {
        EventBus.getDefault().register(this);

        // ---- 顶行: 数据源模式胶囊 / 计数 / 提示 ----
        tvModeTag = findViewById(R.id.tvModeTag);
        tvHotCount = findViewById(R.id.tvHotCount);
        tvHotHint = findViewById(R.id.tvHotHint);
        refreshModeTag();
        // v15.8: 数据源胶囊长按也能切模式(底部入口已折叠, 历史入口不好够到)
        View.OnLongClickListener switchModeListener = new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                HomeActivity.homeRecf();
                return HomeActivity.reHome(mContext);
            }
        };
        tvModeTag.setOnLongClickListener(switchModeListener);

        // ---- 左侧精选大图 Banner ----
        tvFeaturedBanner = findViewById(R.id.tvFeaturedBanner);
        ivBanner = findViewById(R.id.ivBanner);
        tvBannerTitle = findViewById(R.id.tvBannerTitle);
        tvBannerMeta = findViewById(R.id.tvBannerMeta);
        btnBannerCollect = findViewById(R.id.btnBannerCollect);
        tvFeaturedBanner.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                openVod(currentVideo);
            }
        });
        tvFeaturedBanner.setOnFocusChangeListener(focusChangeListener);
        btnBannerCollect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                toggleCollect(currentVideo);
            }
        });
        btnBannerCollect.setOnFocusChangeListener(focusChangeListener);

        // v27.22: 简介 + 圆点指示器
        tvBannerDesc = findViewById(R.id.tvBannerDesc);
        bannerDots = findViewById(R.id.bannerDots);

        // v27.22: 两条横排(正在热映电影 / 热门电影) + 「更多」跳对应分类页
        secHotHeader = findViewById(R.id.secHotHeader);
        secHot2Header = findViewById(R.id.secHot2Header);
        rowHot = findViewById(R.id.rowHot);
        rowHot2 = findViewById(R.id.rowHot2);
        setupRow(rowHot, 0);
        setupRow(rowHot2, 1);
        View moreHot = findViewById(R.id.btnMoreHot);
        if (moreHot != null) moreHot.setOnClickListener(v -> switchSort("电影"));
        View moreHot2 = findViewById(R.id.btnMoreHot2);
        if (moreHot2 != null) moreHot2.setOnClickListener(v -> switchSort("剧"));

        // ---- 海报网格(小贾影视仓 v23 竖屏: 4 列 → 3 列, 卡片够大才看得清片名) ----
        tvHotListForGrid = findViewById(R.id.tvHotGrid);
        tvHotListForGrid.setHasFixedSize(false);
        tvHotListForGrid.setLayoutManager(new V7GridLayoutManager(this.mContext, 3));
        // 小贾影视仓 v23.x: 整页滚动(Banner 随列表滚走), 网格自身不独立滚动
        tvHotListForGrid.setNestedScrollingEnabled(false);
        tvHotListForGrid.setSpacingWithMargins(AutoSizeUtils.dp2px(this.mContext, 12.0f), AutoSizeUtils.dp2px(this.mContext, 14.0f));

        homeHotVodAdapter = new UserHomeRowAdapter(true, R.layout.item_user_home_grid);
        homeHotVodAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                if (ApiConfig.get().getSourceBeanList().isEmpty())
                    return;
                Movie.Video vod = (Movie.Video) adapter.getItem(position);
                openVod(vod);
            }
        });
        // 小贾影视仓 v23: 长按只用于「播放历史」模式下的删除模式; 进入 v23 后不再触发「搜全网」
        homeHotVodAdapter.setOnItemLongClickListener(new BaseQuickAdapter.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(BaseQuickAdapter adapter, View view, int position) {
                if (ApiConfig.get().getSourceBeanList().isEmpty())
                    return false;
                Movie.Video vod = (Movie.Video) adapter.getItem(position);
                if ((vod.id != null && !vod.id.isEmpty()) && (Hawk.get(HawkConfig.HOME_REC, 0) == 2)) {
                    HawkConfig.hotVodDelete = !HawkConfig.hotVodDelete;
                    homeHotVodAdapter.notifyDataSetChanged();
                    return true;
                }
                return false;
            }
        });
        // 焦点联动: 选中卡 -> 放大 + 计数 + Banner 联动
        tvHotListForGrid.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                if (itemView != null) {
                    itemView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).setInterpolator(new DecelerateInterpolator()).start();
                }
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                if (itemView != null) {
                    itemView.animate().scaleX(1.06f).scaleY(1.06f).setDuration(220).setInterpolator(new DecelerateInterpolator()).start();
                }
                focusPos = position;
                updateCount(position);
                showFeatured(position);
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {

            }
        });
        tvHotListForGrid.setAdapter(homeHotVodAdapter);
        // 数据就绪后(豆瓣异步/历史回填/站点推荐)刷新计数与 Banner
        homeHotVodAdapter.registerAdapterDataObserver(new androidx.recyclerview.widget.RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                // v27.22: Banner / 两条横排 / 网格显隐都跟着数据走
                refreshSections();
                if (homeHotVodAdapter == null || homeHotVodAdapter.getData().isEmpty()) return;
                tvHotListForGrid.post(new Runnable() {
                    @Override
                    public void run() {
                        if (homeHotVodAdapter == null || homeHotVodAdapter.getData().isEmpty()) return;
                        int pos = Math.min(focusPos, homeHotVodAdapter.getData().size() - 1);
                        if (pos < 0) pos = 0;
                        focusPos = pos;
                        updateCount(pos);
                        showFeatured(pos);
                    }
                });
            }
        });

        initHomeHotVod(homeHotVodAdapter);
        // v27.23: 两排「热门电影 / 热门剧集」独立取豆瓣榜单, 不再依赖推荐列表长度
        loadRecommendRows();
    }

    // ===== v27.22: 横排 + Banner 轮播 =====

    /** 建一条横排: 横向布局管理器 + 竖版海报卡 + 点击进详情 / 焦点放大 */
    private void setupRow(TvRecyclerView rv, final int which) {
        if (rv == null) return;
        rv.setHasFixedSize(false);
        rv.setLayoutManager(new com.owen.tvrecyclerview.widget.V7LinearLayoutManager(
                mContext, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false));
        rv.setSpacingWithMargins(AutoSizeUtils.dp2px(mContext, 6f), AutoSizeUtils.dp2px(mContext, 6f));
        UserHomeRowAdapter ad = new UserHomeRowAdapter(false, R.layout.item_home_poster_row);
        ad.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                if (ApiConfig.get().getSourceBeanList().isEmpty()) return;
                openVod((Movie.Video) adapter.getItem(position));
            }
        });
        rv.setAdapter(ad);
        rv.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                if (itemView != null) {
                    itemView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200)
                            .setInterpolator(new DecelerateInterpolator()).start();
                }
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                if (itemView != null) {
                    itemView.animate().scaleX(1.06f).scaleY(1.06f).setDuration(220)
                            .setInterpolator(new DecelerateInterpolator()).start();
                }
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
            }
        });
        if (which == 0) rowHotAdapter = ad;
        else rowHot2Adapter = ad;
    }

    /** 「更多」→ 切到名称匹配的分类页(找不到就原地不动, 不弹无意义提示) */
    private void switchSort(String keyword) {
        if (keyword == null || !(mActivity instanceof HomeActivity)) return;
        ((HomeActivity) mActivity).switchSortByName(keyword);
    }

    /**
     * 数据变化后重排首页:
     * 推荐模式 = 大图 Banner + 热门电影 + 热门剧集; 播放历史模式 = 退回 3 列网格。
     */
    private void refreshSections() {
        if (tvBannerDesc == null || rowHotAdapter == null) return; // 视图已销毁
        List<Movie.Video> data = homeHotVodAdapter == null ? null : homeHotVodAdapter.getData();
        if (data == null) data = new ArrayList<>();
        boolean history = (Hawk.get(HawkConfig.HOME_REC, 0) == 2);
        // v27.23: 历史模式但本地一条记录都没有(旧版豆瓣失败时会自动切到这个模式) → 按推荐模式渲染,
        // 否则首页就是整页空白, 用户看到的就是"推荐 电影 剧集 什么都没有"。
        if (history && data.isEmpty()) history = false;
        int n = data.size();

        // 两排: 优先用豆瓣「电影 / 剧集」独立榜单; 两条请求都失败才退回推荐列表切片
        List<Movie.Video> hot = movieRow.isEmpty() ? slice(data, 0, 10) : new ArrayList<>(movieRow);
        List<Movie.Video> hot2 = tvRow.isEmpty() ? slice(data, 10, 20) : new ArrayList<>(tvRow);
        if (history) {
            hot.clear();
            hot2.clear();
        }
        rowHotAdapter.setNewData(hot);
        rowHot2Adapter.setNewData(hot2);
        setSectionVisible(secHotHeader, rowHot, !hot.isEmpty());
        setSectionVisible(secHot2Header, rowHot2, !hot2.isEmpty());
        if (tvHotListForGrid != null) {
            tvHotListForGrid.setVisibility(history ? View.VISIBLE : View.GONE);
        }

        bannerPool.clear();
        if (!history) {
            List<Movie.Video> src = n > 0 ? data : movieRow;
            bannerPool.addAll(src.subList(0, Math.min(5, src.size())));
        }
        if (tvFeaturedBanner != null) {
            tvFeaturedBanner.setVisibility(bannerPool.isEmpty() ? View.GONE : View.VISIBLE);
        }
        showBanner(0);
        startBannerTicker();
    }

    private List<Movie.Video> slice(List<Movie.Video> src, int from, int to) {
        List<Movie.Video> r = new ArrayList<>();
        if (src == null) return r;
        if (src.size() > from) r.addAll(src.subList(from, Math.min(to, src.size())));
        return r;
    }

    // ===== v27.23: 豆瓣「电影 / 剧集」两排独立数据 =====

    private void loadRecommendRows() {
        if (rowsRequested) return;
        rowsRequested = true;
        fetchDoubanRow("movie", movieRow);
        fetchDoubanRow("tv", tvRow);
    }

    /**
     * 取豆瓣「热门」榜单(type=movie / tv)。
     * 失败就静默 —— refreshSections 会退回推荐列表切片, 页面上仍有内容。
     */
    private void fetchDoubanRow(final String type, final List<Movie.Video> target) {
        // tag=热门 (%E7%83%AD%E9%97%A8), 不要加年份过滤: 新版豆瓣按年份筛经常返回空数组
        String url = "https://movie.douban.com/j/search_subjects?type=" + type
                + "&tag=%E7%83%AD%E9%97%A8&sort=recommend&page_limit=20&page_start=0";
        try {
            OkGo.<String>get(url).headers("User-Agent", UA.random()).execute(new AbsCallback<String>() {
                @Override
                public void onSuccess(Response<String> response) {
                    final ArrayList<Movie.Video> list = parseDoubanList(response.body());
                    if (list.isEmpty() || mActivity == null) return;
                    mActivity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (tvBannerDesc == null) return; // 视图已销毁
                            target.clear();
                            target.addAll(list);
                            refreshSections();
                        }
                    });
                }

                @Override
                public String convertResponse(okhttp3.Response response) throws Throwable {
                    return response.body().string();
                }
            });
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /** 兼容两种豆瓣响应: new_search_subjects 的 data[] 与 search_subjects 的 subjects[] */
    private ArrayList<Movie.Video> parseDoubanList(String json) {
        ArrayList<Movie.Video> result = new ArrayList<>();
        if (TextUtils.isEmpty(json)) return result;
        try {
            JsonObject obj = new Gson().fromJson(json, JsonObject.class);
            if (obj == null) return result;
            JsonArray array = obj.has("data") ? obj.getAsJsonArray("data")
                    : obj.has("subjects") ? obj.getAsJsonArray("subjects") : null;
            if (array == null) return result;
            for (JsonElement ele : array) {
                if (!ele.isJsonObject()) continue;
                JsonObject o = (JsonObject) ele;
                String title = optString(o, "title");
                if (TextUtils.isEmpty(title)) continue;
                Movie.Video vod = new Movie.Video();
                vod.name = title;
                vod.note = optString(o, "rate");
                String cover = optString(o, "cover");
                if (TextUtils.isEmpty(cover)) cover = optString(o, "cover_url");
                if (!TextUtils.isEmpty(cover)) {
                    vod.pic = doubanHd(cover, "m") + "@User-Agent=" + UA.random() + "@Referer=https://www.douban.com/";
                }
                result.add(vod);
            }
        } catch (Throwable ignored) {
        }
        return result;
    }

    private String optString(JsonObject o, String key) {
        try {
            JsonElement e = o.get(key);
            if (e == null || e.isJsonNull()) return "";
            return e.getAsString();
        } catch (Throwable th) {
            return "";
        }
    }

    private void setSectionVisible(View header, View row, boolean show) {
        int v = show ? View.VISIBLE : View.GONE;
        if (header != null) header.setVisibility(v);
        if (row != null) row.setVisibility(v);
    }

    /** 把 bannerPool 里第 pos 条铺到 Banner 上(带淡出淡入) */
    private void showBanner(int pos) {
        if (ivBanner == null || tvBannerTitle == null) return;
        if (bannerPool.isEmpty()) {
            updateDots(-1);
            return;
        }
        bannerIndex = ((pos % bannerPool.size()) + bannerPool.size()) % bannerPool.size();
        final Movie.Video vod = bannerPool.get(bannerIndex);
        final int idx = bannerIndex;
        currentVideo = vod;
        ivBanner.animate().alpha(0f).setDuration(140).withEndAction(new Runnable() {
            @Override
            public void run() {
                if (ivBanner == null || tvBannerTitle == null) return;
                if (!TextUtils.isEmpty(vod.pic)) {
                    ImgUtil.loadHq(vod.pic, ivBanner, bannerRadius());
                } else {
                    ivBanner.setImageResource(R.drawable.img_loading_placeholder);
                }
                tvBannerTitle.setText(vod.name == null ? "" : vod.name);
                tvBannerMeta.setText(bannerMeta(vod));
                if (tvBannerDesc != null) tvBannerDesc.setText(bannerDesc(vod));
                if (btnBannerCollect != null) btnBannerCollect.setVisibility(View.GONE);
                updateDots(idx);
                ivBanner.animate().alpha(1f).setDuration(260).start();
            }
        }).start();
    }

    private String bannerMeta(Movie.Video vod) {
        List<String> metas = new ArrayList<>();
        if (isPureNumber(vod.note)) metas.add("★ " + vod.note);
        else if (!TextUtils.isEmpty(vod.note)) metas.add(vod.note);
        if (vod.year > 0) metas.add(vod.year + "");
        if (!TextUtils.isEmpty(vod.type)) metas.add(vod.type);
        else if (!TextUtils.isEmpty(vod.area)) metas.add(vod.area);
        return TextUtils.join(" · ", metas);
    }

    private String bannerDesc(Movie.Video vod) {
        String d = cleanDesc(vod.des);
        if (TextUtils.isEmpty(d)) d = cleanDesc(vod.actor);
        if (!TextUtils.isEmpty(d)) return d;
        return "暂无简介";
    }

    private void updateDots(int active) {
        if (bannerDots == null) return;
        bannerDots.removeAllViews();
        if (bannerPool.size() <= 1) return;
        int size = AutoSizeUtils.mm2px(mContext, 9);
        for (int i = 0; i < bannerPool.size(); i++) {
            View dot = new View(mContext);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.leftMargin = size / 2;
            lp.rightMargin = size / 2;
            dot.setBackgroundResource(i == active
                    ? R.drawable.shape_home_dot_on : R.drawable.shape_home_dot_off);
            bannerDots.addView(dot, lp);
        }
    }

    private void startBannerTicker() {
        stopBannerTicker();
        if (bannerPool.size() <= 1) return;
        bannerHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        bannerHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (ivBanner == null) return;
                showBanner(bannerIndex + 1);
                if (bannerHandler != null) bannerHandler.postDelayed(this, 3500);
            }
        }, 3500);
    }

    private void stopBannerTicker() {
        if (bannerHandler != null) {
            bannerHandler.removeCallbacksAndMessages(null);
            bannerHandler = null;
        }
    }

    // ===== 左侧 Banner 联动 =====

    /**
     * Banner 随右侧网格第 position 项联动: 剧照 / 片名 / 元信息 / 收藏态
     */
    private void showFeatured(int position) {
        // v23.9.1 闪退修复: 切线路重建 Fragment 时, 旧 Fragment 残留的 posted Runnable 仍会回调到这里 ——
        // 它读的静态 homeHotVodAdapter 已被新 Fragment 重新赋值(非空), 但本 Fragment 的 ivBanner 已被
        // onDestroyView 置 null, Glide into(null) 直接抛 NPE("Argument must not be null") 闪退。
        // 视图已销毁就静默返回(新 Fragment 会自己刷新 Banner)。
        if (ivBanner == null || tvBannerTitle == null) {
            return;
        }
        if (homeHotVodAdapter == null || homeHotVodAdapter.getData().isEmpty()) {
            resetFeatured();
            return;
        }
        List<Movie.Video> data = homeHotVodAdapter.getData();
        int pos = Math.max(0, Math.min(position, data.size() - 1));
        Movie.Video vod = data.get(pos);
        currentVideo = vod;
        if (vod == null) {
            resetFeatured();
            return;
        }
        // 剧照
        if (!TextUtils.isEmpty(vod.pic)) {
            ImgUtil.loadHq(vod.pic, ivBanner, bannerRadius());
        } else {
            ivBanner.setImageResource(R.drawable.img_loading_placeholder);
        }
        // 片名
        tvBannerTitle.setText(vod.name == null ? "" : vod.name);
        // 元信息: 评分 · 年份 · 类型 · 地区
        List<String> metas = new ArrayList<>();
        if (isPureNumber(vod.note)) {
            metas.add(vod.note + "分");
        }
        if (vod.year > 0) {
            metas.add(vod.year + "年");
        }
        if (!TextUtils.isEmpty(vod.type)) metas.add(vod.type);
        if (!TextUtils.isEmpty(vod.area)) metas.add(vod.area);
        if (!TextUtils.isEmpty(vod.lang)) metas.add(vod.lang);
        if (metas.isEmpty()) {
            tvBannerMeta.setText("");
            tvBannerMeta.setVisibility(View.GONE);
        } else {
            tvBannerMeta.setText(TextUtils.join(" · ", metas));
            tvBannerMeta.setVisibility(View.VISIBLE);
        }
        // v27.22: 参考图里 Banner 只有"图 + 片名 + 评分 + 简介 + 圆点", 没有收藏钮,
        // 收藏入口统一放详情页 —— 这里恒隐, 避免又冒出一个没人点的按钮。
        if (btnBannerCollect != null) btnBannerCollect.setVisibility(View.GONE);
    }

    private void resetFeatured() {
        currentVideo = null;
        if (tvBannerTitle != null) {
            tvBannerTitle.setText("");
            tvBannerMeta.setVisibility(View.GONE);
            ivBanner.setImageResource(R.drawable.img_loading_placeholder);
            btnBannerCollect.setVisibility(View.GONE);
        }
    }

    /**
     * 收藏/取消收藏(当前 Banner 内容)
     */
    private void toggleCollect(Movie.Video vod) {
        if (vod == null || vod.id == null || vod.id.isEmpty()
                || vod.sourceKey == null || vod.sourceKey.isEmpty()) {
            return;
        }
        boolean collected = RoomDataManger.isVodCollect(vod.sourceKey, vod.id);
        if (collected) {
            RoomDataManger.deleteVodCollect(vod.sourceKey, videoToVodInfo(vod));
            Toast.makeText(mContext, getString(R.string.hm_fav_toast_del), Toast.LENGTH_SHORT).show();
        } else {
            RoomDataManger.insertVodCollect(vod.sourceKey, videoToVodInfo(vod));
            Toast.makeText(mContext, getString(R.string.hm_fav_toast_add), Toast.LENGTH_SHORT).show();
        }
        refreshCollectState(vod);
    }

    private void refreshCollectState(Movie.Video vod) {
        if (btnBannerCollect == null || vod == null) return;
        boolean collected = RoomDataManger.isVodCollect(vod.sourceKey, vod.id);
        btnBannerCollect.setText(collected ? getString(R.string.hm_btn_collected) : getString(R.string.hm_btn_collect));
    }

    // ===== 网格(三模式) =====

    /**
     * 顶部数量统计 —— "第 N 个 / 共 M 个"
     */
    private void updateCount(int position) {
        if (tvHotCount == null) return;
        int total = homeHotVodAdapter == null ? 0 : homeHotVodAdapter.getData().size();
        if (total <= 0) {
            tvHotCount.setText("");
            return;
        }
        int cur = Math.max(1, Math.min(position + 1, total));
        tvHotCount.setText(cur + " / " + total);
    }

    private void refreshModeTag() {
        if (tvModeTag == null) return;
        int mode = Hawk.get(HawkConfig.HOME_REC, 0);
        if (mode == 0) {
            tvModeTag.setText(getString(R.string.hm_mode_douban));
        } else if (mode == 1) {
            tvModeTag.setText(getString(R.string.hm_mode_site));
        } else {
            tvModeTag.setText(getString(R.string.hm_mode_history));
        }
    }

    /**
     * 历史记录(VodInfo) -> 首页 Movie.Video, 带"上次看到xx"进度备注
     */
    private List<Movie.Video> historyToVideos(List<VodInfo> records) {
        List<Movie.Video> videos = new ArrayList<>();
        if (records == null) return videos;
        for (VodInfo vodInfo : records) {
            if (vodInfo == null || vodInfo.name == null) continue;
            Movie.Video vod = new Movie.Video();
            vod.id = vodInfo.id;
            vod.sourceKey = vodInfo.sourceKey;
            vod.name = vodInfo.name;
            vod.pic = vodInfo.pic;
            vod.year = vodInfo.year;
            vod.type = vodInfo.type;
            vod.des = vodInfo.des;
            if (vodInfo.playNote != null && !vodInfo.playNote.isEmpty()) {
                vod.note = "上次看到" + vodInfo.playNote;
            }
            videos.add(vod);
        }
        return videos;
    }

    private VodInfo videoToVodInfo(Movie.Video vod) {
        VodInfo info = new VodInfo();
        info.id = vod.id;
        info.name = vod.name;
        info.pic = vod.pic;
        info.year = vod.year;
        info.type = vod.type;
        return info;
    }

    // ===== 统一打开/搜索 =====

    private void startFastSearch(Movie.Video vod) {
        if (vod == null) return;
        Intent newIntent = new Intent(mContext, FastSearchActivity.class);
        newIntent.putExtra("title", vod.name);
        newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        mActivity.startActivity(newIntent);
    }

    /**
     * 统一打开逻辑 —— 删除模式移除历史 / 全网搜 / 详情
     */
    /** 小贾影视仓 v27.18: 一次点击只允许发起一次跳转。 */
    private long lastOpenVodTs = 0L;

    private void openVod(Movie.Video vod) {
        if (vod == null) return;
        // 小贾影视仓 v27.18: 防重入。
        // Banner 和网格卡都挂了点击, TvRecyclerView 在触摸模式下还会先派发一次"选中",
        // 同一下手指能连着走到两次 openVod —— 第一次拿到的 vod.id 为空(走"按片名搜"),
        // 于是"先闪一下搜索页, 再进播放页"。这里做 900ms 单通道, 一次点击只认第一个。
        long now = System.currentTimeMillis();
        if (now - lastOpenVodTs < 900L) return;
        lastOpenVodTs = now;
        if (ApiConfig.get().getSourceBeanList().isEmpty())
            return;
        // takagen99: Check if in Delete Mode
        if ((vod.id != null && !vod.id.isEmpty()) && (Hawk.get(HawkConfig.HOME_REC, 0) == 2) && HawkConfig.hotVodDelete) {
            homeHotVodAdapter.remove(homeHotVodAdapter.getData().indexOf(vod));
            VodInfo vodInfo = RoomDataManger.getVodInfo(vod.sourceKey, vod.id);
            RoomDataManger.deleteVodRecord(vod.sourceKey, vodInfo);
            Toast.makeText(mContext, getString(R.string.hm_hist_del), Toast.LENGTH_SHORT).show();
        } else if (vod.id != null && !vod.id.isEmpty()) {
            Bundle bundle = new Bundle();
            bundle.putString("id", vod.id);
            bundle.putString("sourceKey", vod.sourceKey);
            // 小贾影视仓 v26.8: 带上片名与封面 —— 直达播放模式下详情页只当数据中转,
            // 进场骨架(虚化封面+片名)靠这两个字段, 否则只能是黑屏转圈。
            bundle.putString("title", vod.name);
            bundle.putString("pic", vod.pic);
            if (vod.id.startsWith("msearch:")) {
                // v27.28: msearch: 伪 id 也是"要按片名搜", 但不再弹搜索页, 静默匹配后直接进详情
                com.github.tvbox.osc.util.SilentOpen.openByName(mActivity, vod.sourceKey, vod.name);
            } else {
                jumpActivity(DetailActivity.class, bundle);
            }
        } else if (Hawk.get(HawkConfig.HOME_REC, 0) == 1 && tryOpenByName(vod.name)) {
            // 站点推荐模式下条目本该带 id —— 若为空, 先按片名在当前推荐里找一条带 id 的替身,
            // 找到就直接进播放页, 不再把用户甩到搜索页。
        } else {
            // 小贾影视仓 v27.28: 没 id 的条目(豆瓣兜底/banner/站点给的 msearch: 伪 id)不再拉搜索页,
            // 改成「按片名静默搜索当前站点 → 命中直接进详情」。用户只看到一句提示,
            // 不会再出现"点封面先闪一下搜索页"。
            com.github.tvbox.osc.util.SilentOpen.openByName(mActivity, vod.sourceKey, vod.name);
        }
    }

    /**
     * 小贾影视仓 v27.18: 首页条目 id 为空时, 按片名在同批推荐数据里找一条带 id 的。
     * 找到就直接进播放页 —— 用户要的是"点一次就进去", 不是"先闪一下搜索页"。
     */
    private boolean tryOpenByName(String name) {
        if (name == null || name.trim().isEmpty()) return false;
        String target = name.trim();
        java.util.List<Movie.Video> pool = homeHotVodAdapter == null ? null : homeHotVodAdapter.getData();
        if (pool == null || pool.isEmpty()) return false;
        for (Movie.Video v : pool) {
            if (v == null || v.id == null || v.id.isEmpty()) continue;
            if (target.equals(v.name == null ? "" : v.name.trim())) {
                Bundle bundle = new Bundle();
                bundle.putString("id", v.id);
                bundle.putString("sourceKey", v.sourceKey);
                bundle.putString("title", v.name);
                bundle.putString("pic", v.pic);
                jumpActivity(DetailActivity.class, bundle);
                return true;
            }
        }
        return false;
    }

    private void initHomeHotVod(UserHomeRowAdapter adapter) {
        if (Hawk.get(HawkConfig.HOME_REC, 0) == 1) {
            if (homeSourceRec != null && !homeSourceRec.isEmpty()) {
                adapter.setNewData(homeSourceRec);
                return;
            }
            // 小贾影视仓 v24.4: 首次打开推荐空白的根因 —— 默认模式是"站点推荐"(HOME_REC=1),
            // 冷启动时 HomeActivity 还没拿到站点的首页数据, homeSourceRec=null, 这里原来
            // 直接 return 且之后没有任何回填, 首屏推荐永远空白, 必须重进才有。
            // 现在站点数据未就绪时落到下面的豆瓣/历史兜底链, 首开即有内容;
            // 站点数据到位后(重进/切线路重建页面)自然恢复站点推荐。
            // 小贾影视仓 v26.3: 用户明确要求"首页推荐改成站点推荐" —— 未就绪时**不再直接掉豆瓣**,
            // 先回显当天该站的推荐落盘缓存(getSort 成功时都会写), 缓存也空了才走豆瓣/历史兜底。
            try {
                SourceBean home = ApiConfig.get().getHomeSourceBean();
                String homeKey = home == null ? null : home.getKey();
                if (homeKey != null) {
                    java.util.List<Movie.Video> cached =
                            SourceViewModel.getHomeRecVideosCached(homeKey);
                    if (cached != null && !cached.isEmpty()) {
                        adapter.setNewData(cached);
                        return;
                    }
                }
            } catch (Throwable ignored) {
            }
        } else if (Hawk.get(HawkConfig.HOME_REC, 0) == 2) {
            adapter.setNewData(historyToVideos(RoomDataManger.getAllVodRecord(30)));
            return;
        }
        try {
            Calendar cal = Calendar.getInstance();
            int year = cal.get(Calendar.YEAR);
            int month = cal.get(Calendar.MONTH) + 1;
            int day = cal.get(Calendar.DATE);
            String today = String.format("%d%d%d", year, month, day);
            String requestDay = Hawk.get("home_hot_day", "");
            if (requestDay.equals(today)) {
                String json = Hawk.get("home_hot", "");
                // v24.3: 当日缓存可能是被豆瓣拦截的垃圾响应(解析为空) —— 空列表不能直接 return,
                // 否则"推荐空白"会被缓存一整天。空则跌到下面的 last 兜底/重拉逻辑。
                java.util.ArrayList<Movie.Video> cached = json.isEmpty() ? new java.util.ArrayList<>() : loadHots(json);
                if (!cached.isEmpty()) {
                    adapter.setNewData(cached);
                    return;
                }
            }
            // 小贾影视仓 v24.3: 豆瓣榜单兜底 —— 用户反馈"主页影视推荐没有"。
            // 原实现请求失败当天就永久空白(缓存只在成功时写)。现在:
            // ① 先用上次成功榜单(home_hot_last, 不限期)立即兜底显示;
            // ② 再异步刷新豆瓣, 成功则同时更新当日缓存与 last 缓存;
            // ③ 失败且没有任何缓存 → 自动切到播放历史模式, 主页永远不空。
            String lastJson = Hawk.get("home_hot_last", "");
            boolean showedStale = false;
            if (!lastJson.isEmpty()) {
                java.util.ArrayList<Movie.Video> stale = loadHots(lastJson);
                if (!stale.isEmpty()) {
                    adapter.setNewData(stale);
                    showedStale = true;
                }
            }
            final boolean staleShown = showedStale;
            // v27.23: 去掉 year_range 过滤 —— 按「今年」筛豆瓣经常返回空数组,
            // 首屏推荐就整块空白(用户反馈"推荐 电影 剧集怎么都没有")。不带年份永远有 20 条。
            String doubanHotURL = "https://movie.douban.com/j/new_search_subjects?sort=U&range=0,10&tags=&playable=1&start=0";
            String userAgent = UA.random();
            OkGo.<String>get(doubanHotURL).headers("User-Agent", userAgent).execute(new AbsCallback<String>() {
                @Override
                public void onSuccess(Response<String> response) {
                    final String netJson = response.body();
                    mActivity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            java.util.ArrayList<Movie.Video> fresh = loadHots(netJson);
                            if (!fresh.isEmpty()) {
                                // 小贾影视仓 v24.4: 只缓存解析非空的榜单 —— 被拦截的垃圾响应
                                // 不再写进当日/last 缓存(否则空榜单会被缓存一整天)。
                                Hawk.put("home_hot_day", today);
                                Hawk.put("home_hot", netJson);
                                Hawk.put("home_hot_last", netJson);
                                adapter.setNewData(fresh);
                            } else if (!staleShown) {
                                switchToHistoryFallback(adapter);
                            }
                        }
                    });
                }

                @Override
                public void onError(Response<String> response) {
                    super.onError(response);
                    mActivity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (!staleShown) {
                                switchToHistoryFallback(adapter);
                            }
                        }
                    });
                }

                @Override
                public String convertResponse(okhttp3.Response response) throws Throwable {
                    return response.body().string();
                }
            });
        } catch (Throwable th) {
            th.printStackTrace();
            switchToHistoryFallback(adapter);
        }
    }

    /** 小贾影视仓 v24.3: 豆瓣榜单一无所有时, 自动切"播放历史"模式并提示, 主页不再空白 */
    private void switchToHistoryFallback(UserHomeRowAdapter adapter) {
        try {
            if (adapter == null || (adapter.getData() != null && !adapter.getData().isEmpty())) return;
            Hawk.put(HawkConfig.HOME_REC, 2);
            adapter.setNewData(historyToVideos(RoomDataManger.getAllVodRecord(30)));
            refreshModeTag();
            if (mActivity != null) {
                android.widget.Toast.makeText(mActivity, "豆瓣榜单获取失败，已切换到播放历史", android.widget.Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private ArrayList<Movie.Video> loadHots(String json) {
        ArrayList<Movie.Video> result = new ArrayList<>();
        try {
            JsonObject infoJson = new Gson().fromJson(json, JsonObject.class);
            JsonArray array = infoJson.getAsJsonArray("data");
            for (JsonElement ele : array) {
                JsonObject obj = (JsonObject) ele;
                Movie.Video vod = new Movie.Video();
                vod.name = obj.get("title").getAsString();
                vod.note = obj.get("rate").getAsString();
                vod.pic = doubanHd(obj.get("cover").getAsString(), "l")
                        + "@User-Agent=" + UA.random() + "@Referer=https://www.douban.com/";
                result.add(vod);
            }
        } catch (Throwable th) {

        }
        return result;
    }

    // ===== 工具 =====

    /**
     * 小贾影视仓 v27.27: 豆瓣封面换成更高清的尺寸。
     * 接口默认给的是 s_ratio_poster(实测 270×359), 铺到首页横排(≈270px 宽)刚好,
     * 但铺到大图 Banner(≈1080px 宽)就是 4 倍放大 —— 用户看到的就是"横屏推荐图糊"。
     * 同一张图豆瓣有 m(540×719) / l(1080×1439) 两档, 换掉路径段即可, 无需额外请求。
     */
    private static String doubanHd(String url, String size) {
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(size)) return url;
        if (url.contains("/s_ratio_poster/")) {
            return url.replace("/s_ratio_poster/", "/" + size + "_ratio_poster/");
        }
        return url;
    }

    /** Banner 圆角(px) —— 与 shape_home_banner 一致, 图片自身也要切出圆弧。 */
    private int bannerRadius() {
        try {
            return getResources().getDimensionPixelSize(R.dimen.vs_44);
        } catch (Throwable t) {
            return 36;
        }
    }

    private boolean isPureNumber(String s) {
        if (s == null || s.isEmpty()) return false;
        try {
            Float.parseFloat(s);
            return true;
        } catch (Throwable th) {
            return false;
        }
    }

    /**
     * 简介清洗: 去掉 XStream CDATA 包裹与 HTML 标签, 压缩空白
     */
    private String cleanDesc(String s) {
        if (TextUtils.isEmpty(s)) return "";
        String r = s.replace("// <![CDATA[", "").replace("<![CDATA[", "")
                .replace("]]>", "")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replaceAll("\\s+", " ")
                .trim();
        return r;
    }

    private final View.OnFocusChangeListener focusChangeListener = new View.OnFocusChangeListener() {
        @Override
        public void onFocusChange(View v, boolean hasFocus) {
            if (hasFocus)
                v.animate().scaleX(1.06f).scaleY(1.06f).setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
            else
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
        }
    };

    @Override
    public void onClick(View v) {
        // 小贾影视仓 v23.x: 底部 dock 已移除, 无按钮使用本回调; 保留接口兼容
        FastClickCheckUtil.check(v);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void server(ServerEvent event) {
        if (event.type == ServerEvent.SERVER_CONNECTION) {
        }
    }

    @Override
    public void onDestroyView() {
        stopBannerTicker();
        // v15.6.1: tvHotListForGrid/homeHotVodAdapter 是静态字段, 若不置空会一直钉住已销毁的
        // RecyclerView 连同其 Context(Activity) 一起泄漏 —— 每次切线路重建 fragments 就多漏一份。
        // HomeActivity 访问均已判空, 置空安全。
        if (tvHotListForGrid != null) {
            tvHotListForGrid.setAdapter(null);
            tvHotListForGrid = null;
        }
        if (rowHot != null) {
            rowHot.setAdapter(null);
            rowHot = null;
        }
        if (rowHot2 != null) {
            rowHot2.setAdapter(null);
            rowHot2 = null;
        }
        homeHotVodAdapter = null;
        rowHotAdapter = null;
        rowHot2Adapter = null;
        tvFeaturedBanner = null;
        ivBanner = null;
        tvBannerTitle = null;
        tvBannerMeta = null;
        tvBannerDesc = null;
        bannerDots = null;
        secHotHeader = null;
        secHot2Header = null;
        btnBannerCollect = null;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
    }
}
