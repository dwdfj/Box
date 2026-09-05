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
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cache.RoomDataManger;
import com.github.tvbox.osc.event.ServerEvent;
import com.github.tvbox.osc.ui.activity.*;
import com.github.tvbox.osc.ui.adapter.UserHomeRowAdapter;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.UA;
import com.github.tvbox.osc.util.ImgUtil;
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
 * 4. 底部入口收成 1 个按钮(tvDockBtn), 点击才展开 5 项面板(历史/直播/收藏/推送/网盘)。
 * 保留: 三模式切换(长按历史入口或数据源胶囊)、长按搜全网、播放历史删除模式、
 *      v15.6.1 静态字段泄漏修复(onDestroyView 置空)、HomeActivity 通过 tvHotListForGrid 回滚。
 */
public class UserFragment extends BaseLazyFragment implements View.OnClickListener {
    private LinearLayout tvSearch;                    // v15.12: Dock 面板首位搜索入口
    private LinearLayout tvDrive;
    private LinearLayout tvLive;
    private LinearLayout tvHistory;
    private LinearLayout tvCollect;
    private LinearLayout tvPush;
    private LinearLayout tvUserHome;                  // 底部入口折叠面板(默认 GONE)
    private LinearLayout tvDockBtn;                   // 底部「更多」按钮(点击展开/收起面板)
    private boolean dockExpanded = false;
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

        // ---- 底部入口面板(默认收起) + 展开按钮 ----
        tvUserHome = findViewById(R.id.tvUserHome);
        tvDockBtn = findViewById(R.id.tvDockBtn);
        tvSearch = findViewById(R.id.tvSearch);
        tvDrive = findViewById(R.id.tvDrive);
        tvLive = findViewById(R.id.tvLive);
        tvCollect = findViewById(R.id.tvFavorite);
        tvHistory = findViewById(R.id.tvHistory);
        tvPush = findViewById(R.id.tvPush);
        tvSearch.setOnClickListener(this);
        tvDrive.setOnClickListener(this);
        tvLive.setOnClickListener(this);
        tvHistory.setOnClickListener(this);
        tvPush.setOnClickListener(this);
        tvCollect.setOnClickListener(this);
        tvSearch.setOnFocusChangeListener(focusChangeListener);
        tvDrive.setOnFocusChangeListener(focusChangeListener);
        tvLive.setOnFocusChangeListener(focusChangeListener);
        tvHistory.setOnFocusChangeListener(focusChangeListener);
        tvPush.setOnFocusChangeListener(focusChangeListener);
        tvCollect.setOnFocusChangeListener(focusChangeListener);
        tvDockBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                toggleDock();
            }
        });
        tvDockBtn.setOnFocusChangeListener(focusChangeListener);

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
        tvHistory.setOnLongClickListener(switchModeListener);

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

        // ---- 右侧多列海报网格(默认 4 列, 横屏铺满) ----
        tvHotListForGrid = findViewById(R.id.tvHotGrid);
        tvHotListForGrid.setHasFixedSize(true);
        tvHotListForGrid.setLayoutManager(new V7GridLayoutManager(this.mContext, 4));
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
        // takagen99: 长按 —— 播放历史模式下进入/退出删除模式, 其余长按搜全网
        homeHotVodAdapter.setOnItemLongClickListener(new BaseQuickAdapter.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(BaseQuickAdapter adapter, View view, int position) {
                if (ApiConfig.get().getSourceBeanList().isEmpty())
                    return false;
                Movie.Video vod = (Movie.Video) adapter.getItem(position);
                if ((vod.id != null && !vod.id.isEmpty()) && (Hawk.get(HawkConfig.HOME_REC, 0) == 2)) {
                    HawkConfig.hotVodDelete = !HawkConfig.hotVodDelete;
                    homeHotVodAdapter.notifyDataSetChanged();
                } else {
                    startFastSearch(vod);
                }
                return true;
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
    }

    // ===== 底部入口: 折叠 / 展开 =====

    private void toggleDock() {
        if (tvUserHome == null) return;
        dockExpanded = !dockExpanded;
        tvUserHome.setVisibility(dockExpanded ? View.VISIBLE : View.GONE);
        if (dockExpanded && tvSearch != null) {
            // v15.12: Dock 面板首位是「搜索」, 展开默认焦点落搜索, 按 OK 即搜
            tvSearch.post(new Runnable() {
                @Override
                public void run() {
                    tvSearch.requestFocus();
                }
            });
        } else if (tvDockBtn != null) {
            tvDockBtn.requestFocus();
        }
    }

    private void collapseDock() {
        dockExpanded = false;
        if (tvUserHome != null) {
            tvUserHome.setVisibility(View.GONE);
        }
    }

    // ===== 左侧 Banner 联动 =====

    /**
     * Banner 随右侧网格第 position 项联动: 剧照 / 片名 / 元信息 / 收藏态
     */
    private void showFeatured(int position) {
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
            ImgUtil.load(vod.pic, ivBanner, 14);
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
        // 收藏钮: 仅站点/历史类有真实 id+sourceKey 的内容可收藏(豆瓣热播无 id, 播放即全网搜)
        if (vod.id != null && !vod.id.isEmpty() && vod.sourceKey != null && !vod.sourceKey.isEmpty()) {
            btnBannerCollect.setVisibility(View.VISIBLE);
            refreshCollectState(vod);
        } else {
            btnBannerCollect.setVisibility(View.GONE);
        }
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
    private void openVod(Movie.Video vod) {
        if (vod == null) return;
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
            if (vod.id.startsWith("msearch:")) {
                bundle.putString("title", vod.name);
                jumpActivity(FastSearchActivity.class, bundle);
            } else {
                jumpActivity(DetailActivity.class, bundle);
            }
        } else {
            // 豆瓣热播等无 id 内容: 按片名全网搜
            Intent newIntent;
            if (Hawk.get(HawkConfig.FAST_SEARCH_MODE, false)) {
                newIntent = new Intent(mContext, FastSearchActivity.class);
            } else {
                newIntent = new Intent(mContext, SearchActivity.class);
            }
            newIntent.putExtra("title", vod.name);
            newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            mActivity.startActivity(newIntent);
        }
    }

    private void initHomeHotVod(UserHomeRowAdapter adapter) {
        if (Hawk.get(HawkConfig.HOME_REC, 0) == 1) {
            if (homeSourceRec != null) {
                adapter.setNewData(homeSourceRec);
            }
            return;
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
                if (!json.isEmpty()) {
                    adapter.setNewData(loadHots(json));
                    return;
                }
            }
            String doubanHotURL = "https://movie.douban.com/j/new_search_subjects?sort=U&range=0,10&tags=&playable=1&start=0&year_range=" + year + "," + year;
            String userAgent = UA.random();
            OkGo.<String>get(doubanHotURL).headers("User-Agent", userAgent).execute(new AbsCallback<String>() {
                @Override
                public void onSuccess(Response<String> response) {
                    String netJson = response.body();
                    Hawk.put("home_hot_day", today);
                    Hawk.put("home_hot", netJson);
                    mActivity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            adapter.setNewData(loadHots(netJson));
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
                vod.pic = obj.get("cover").getAsString() + "@User-Agent=" + UA.random() + "@Referer=https://www.douban.com/";
                result.add(vod);
            }
        } catch (Throwable th) {

        }
        return result;
    }

    // ===== 工具 =====

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
        // takagen99: Remove Delete Mode
        HawkConfig.hotVodDelete = false;

        FastClickCheckUtil.check(v);
        // 入口用完即收起(跳走前先折叠, 返回首页时面板是干净的收起态)
        collapseDock();
        if (v.getId() == R.id.tvSearch) {
            // v15.12: Dock 面板搜索入口 → 搜索页
            jumpActivity(SearchActivity.class);
        } else if (v.getId() == R.id.tvLive) {
            jumpActivity(LivePlayActivity.class);
        } else if (v.getId() == R.id.tvHistory) {
            jumpActivity(HistoryActivity.class);
        } else if (v.getId() == R.id.tvPush) {
            jumpActivity(PushActivity.class);
        } else if (v.getId() == R.id.tvFavorite) {
            jumpActivity(CollectActivity.class);
        } else if (v.getId() == R.id.tvDrive) {
            jumpActivity(DriveActivity.class);
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void server(ServerEvent event) {
        if (event.type == ServerEvent.SERVER_CONNECTION) {
        }
    }

    @Override
    public void onDestroyView() {
        // v15.6.1: tvHotListForGrid/homeHotVodAdapter 是静态字段, 若不置空会一直钉住已销毁的
        // RecyclerView 连同其 Context(Activity) 一起泄漏 —— 每次切线路重建 fragments 就多漏一份。
        // HomeActivity 访问均已判空, 置空安全。
        if (tvHotListForGrid != null) {
            tvHotListForGrid.setAdapter(null);
            tvHotListForGrid = null;
        }
        homeHotVodAdapter = null;
        tvUserHome = null;
        tvDockBtn = null;
        tvSearch = null;
        tvFeaturedBanner = null;
        ivBanner = null;
        tvBannerTitle = null;
        tvBannerMeta = null;
        btnBannerCollect = null;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
    }
}
