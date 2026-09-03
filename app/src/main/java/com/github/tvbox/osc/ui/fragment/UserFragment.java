package com.github.tvbox.osc.ui.fragment;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
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
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

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
 * @description: 小贾影视仓 v15.7 —— 首页(my0页签)改为「方案A: 主推横卡 + 双卡带」:
 * 顶部「今日主推」横卡(大图 + 片名/元信息/简介 + 播放·收藏钮, 约 1/3 内容区高);
 * 「热门推荐」横向卡带(豆瓣热播/站点推荐/播放历史 三模式数据源, 焦点横向条联动主推卡与计数);
 * 「继续观看」横向卡带(本地播放历史, HOME_REC==2 时隐藏避免与主带重复);
 * 底部 5 入口 dock(历史/直播/收藏/推送/网盘, 搜索/菜单迁至顶栏图标)。
 * 保留: 三模式长按切换、长按搜全网、播放历史删除模式、v15.6.1 静态字段泄漏修复。
 */
public class UserFragment extends BaseLazyFragment implements View.OnClickListener {
    private LinearLayout tvDrive;
    private LinearLayout tvLive;
    private LinearLayout tvHistory;
    private LinearLayout tvCollect;
    private LinearLayout tvPush;
    public static UserHomeRowAdapter homeHotVodAdapter;   // 热门推荐卡带(静态: HomeActivity 删除模式退出时刷新)
    public static TvRecyclerView tvHotListForGrid;        // 热门推荐卡带容器(静态: HomeActivity 返回键回滚)
    private TvRecyclerView tvRecentList;                  // 继续观看卡带(仅本页使用)
    private UserHomeRowAdapter recentAdapter;             // 继续观看适配器(不带删除模式)
    private LinearLayout tvRecentSection;                 // 继续观看整段(可整体隐藏)
    private List<Movie.Video> homeSourceRec;              // 站点推荐数据(模式1)
    private Movie.Video currentVideo;                     // 主推横卡当前内容

    // 顶部主推横卡控件
    private TextView tvModeTag;
    private TextView tvHotCount;
    private TextView tvHotHint;
    private ImageView tvFeaturedThumb;
    private TextView tvFeaturedTitle;
    private TextView tvFeaturedMeta;
    private TextView tvFeaturedDesc;
    private TextView tvFeaturedPlay;
    private TextView tvFeaturedCollect;

    private int focusPos = 0;
    private static final int RECENT_LIMIT = 12;

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
        if (tvModeTag == null || tvFeaturedTitle == null) {
            return;
        }
        // 三模式为「播放历史」时: 由本地历史回填热门推荐卡带(v15.6 同语义)
        if (Hawk.get(HawkConfig.HOME_REC, 0) == 2 && homeHotVodAdapter != null) {
            homeHotVodAdapter.setNewData(historyToVideos(RoomDataManger.getAllVodRecord(20)));
        }
        refreshRecentLane();
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_user;
    }

    @Override
    protected void init() {
        EventBus.getDefault().register(this);
        tvDrive = findViewById(R.id.tvDrive);
        tvLive = findViewById(R.id.tvLive);
        tvCollect = findViewById(R.id.tvFavorite);
        tvHistory = findViewById(R.id.tvHistory);
        tvPush = findViewById(R.id.tvPush);
        tvDrive.setOnClickListener(this);
        tvLive.setOnClickListener(this);
        tvHistory.setOnClickListener(this);
        tvPush.setOnClickListener(this);
        tvCollect.setOnClickListener(this);
        tvDrive.setOnFocusChangeListener(focusChangeListener);
        tvLive.setOnFocusChangeListener(focusChangeListener);
        tvHistory.setOnFocusChangeListener(focusChangeListener);
        tvPush.setOnFocusChangeListener(focusChangeListener);
        tvCollect.setOnFocusChangeListener(focusChangeListener);

        // ---- 顶部「今日主推」横卡 ----
        tvModeTag = findViewById(R.id.tvModeTag);
        tvHotCount = findViewById(R.id.tvHotCount);
        tvHotHint = findViewById(R.id.tvHotHint);
        tvFeaturedThumb = findViewById(R.id.tvFeaturedThumb);
        tvFeaturedTitle = findViewById(R.id.tvFeaturedTitle);
        tvFeaturedMeta = findViewById(R.id.tvFeaturedMeta);
        tvFeaturedDesc = findViewById(R.id.tvFeaturedDesc);
        tvFeaturedPlay = findViewById(R.id.tvFeaturedPlay);
        tvFeaturedCollect = findViewById(R.id.tvFeaturedCollect);
        tvFeaturedPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                openVod(currentVideo);
            }
        });
        tvFeaturedPlay.setOnFocusChangeListener(focusChangeListener);
        tvFeaturedCollect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                toggleCollect(currentVideo);
            }
        });
        tvFeaturedCollect.setOnFocusChangeListener(focusChangeListener);
        refreshModeTag();

        // ---- ① 热门推荐卡带(三模式数据源, 横向焦点条) ----
        tvHotListForGrid = findViewById(R.id.tvHotListForGrid);
        tvHotListForGrid.setHasFixedSize(true);
        tvHotListForGrid.setLayoutManager(new V7LinearLayoutManager(this.mContext, 0, false)); // 0=HORIZONTAL
        tvHotListForGrid.setSpacingWithMargins(AutoSizeUtils.dp2px(this.mContext, 0.0f), AutoSizeUtils.dp2px(this.mContext, 14.0f));

        homeHotVodAdapter = new UserHomeRowAdapter(true);
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
        // 横向焦点条: 选中卡 -> 主推横卡联动 + 计数 + 轻微放大
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
                    itemView.animate().scaleX(1.08f).scaleY(1.08f).setDuration(220).setInterpolator(new DecelerateInterpolator()).start();
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
        // 数据就绪后(豆瓣异步/历史回填/站点推荐)刷新计数与主推卡
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

        // ---- ② 继续观看卡带(本地播放历史, 独立于三模式) ----
        tvRecentSection = findViewById(R.id.tvRecentSection);
        tvRecentList = findViewById(R.id.tvRecentList);
        tvRecentList.setHasFixedSize(true);
        tvRecentList.setLayoutManager(new V7LinearLayoutManager(this.mContext, 0, false)); // 0=HORIZONTAL
        tvRecentList.setSpacingWithMargins(AutoSizeUtils.dp2px(this.mContext, 0.0f), AutoSizeUtils.dp2px(this.mContext, 14.0f));
        recentAdapter = new UserHomeRowAdapter(false);
        recentAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                if (ApiConfig.get().getSourceBeanList().isEmpty())
                    return;
                Movie.Video vod = (Movie.Video) adapter.getItem(position);
                openVod(vod);
            }
        });
        recentAdapter.setOnItemLongClickListener(new BaseQuickAdapter.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(BaseQuickAdapter adapter, View view, int position) {
                if (ApiConfig.get().getSourceBeanList().isEmpty())
                    return false;
                Movie.Video vod = (Movie.Video) adapter.getItem(position);
                startFastSearch(vod);
                return true;
            }
        });
        tvRecentList.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                if (itemView != null) {
                    itemView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).setInterpolator(new DecelerateInterpolator()).start();
                }
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                if (itemView != null) {
                    itemView.animate().scaleX(1.08f).scaleY(1.08f).setDuration(220).setInterpolator(new DecelerateInterpolator()).start();
                }
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {

            }
        });
        tvRecentList.setAdapter(recentAdapter);
        refreshRecentLane();

        tvHistory.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                HomeActivity.homeRecf();
                return HomeActivity.reHome(mContext);
            }
        });

        initHomeHotVod(homeHotVodAdapter);
    }

    // ===== 主推横卡 =====

    /**
     * 主推横卡联动热门推荐卡带第 position 项: 大图/片名/元信息/简介/收藏态
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
        // 大图
        if (!TextUtils.isEmpty(vod.pic)) {
            ImgUtil.load(vod.pic, tvFeaturedThumb, 14);
        } else {
            tvFeaturedThumb.setImageResource(R.drawable.img_loading_placeholder);
        }
        // 片名
        tvFeaturedTitle.setText(vod.name == null ? "" : vod.name);
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
            tvFeaturedMeta.setText("");
            tvFeaturedMeta.setVisibility(View.GONE);
        } else {
            tvFeaturedMeta.setText(TextUtils.join(" · ", metas));
            tvFeaturedMeta.setVisibility(View.VISIBLE);
        }
        // 简介(去 CDATA/HTML 标签, 至多两行)
        String desc = cleanDesc(vod.des);
        if (TextUtils.isEmpty(desc)) {
            tvFeaturedDesc.setText("");
            tvFeaturedDesc.setVisibility(View.GONE);
        } else {
            tvFeaturedDesc.setText(desc);
            tvFeaturedDesc.setVisibility(View.VISIBLE);
        }
        // 收藏钮: 仅站点/历史类有真实 id+sourceKey 的内容可收藏(豆瓣热播无 id, 播放即全网搜)
        if (vod.id != null && !vod.id.isEmpty() && vod.sourceKey != null && !vod.sourceKey.isEmpty()) {
            tvFeaturedCollect.setVisibility(View.VISIBLE);
            refreshCollectState(vod);
        } else {
            tvFeaturedCollect.setVisibility(View.GONE);
        }
    }

    private void resetFeatured() {
        currentVideo = null;
        if (tvFeaturedThumb != null) {
            tvFeaturedThumb.setImageResource(R.drawable.img_loading_placeholder);
            tvFeaturedTitle.setText("");
            tvFeaturedMeta.setVisibility(View.GONE);
            tvFeaturedDesc.setVisibility(View.GONE);
            tvFeaturedCollect.setVisibility(View.GONE);
        }
    }

    /**
     * 收藏/取消收藏(当前主推内容)
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
        if (tvFeaturedCollect == null || vod == null) return;
        boolean collected = RoomDataManger.isVodCollect(vod.sourceKey, vod.id);
        tvFeaturedCollect.setText(collected ? getString(R.string.hm_btn_collected) : getString(R.string.hm_btn_collect));
    }

    // ===== 热门推荐卡带(三模式) =====

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

    // ===== 继续观看卡带(本地历史) =====

    /**
     * 刷新「继续观看」: 数据 = 本地播放历史; HOME_REC==2(主带已是历史)或历史为空时整段隐藏
     */
    private void refreshRecentLane() {
        if (tvRecentList == null || recentAdapter == null) return;
        boolean hideAll = Hawk.get(HawkConfig.HOME_REC, 0) == 2;
        if (hideAll) {
            tvRecentSection.setVisibility(View.GONE);
            return;
        }
        List<Movie.Video> videos = historyToVideos(RoomDataManger.getAllVodRecord(RECENT_LIMIT));
        if (videos.isEmpty()) {
            tvRecentSection.setVisibility(View.GONE);
            return;
        }
        recentAdapter.setNewData(videos);
        tvRecentSection.setVisibility(View.VISIBLE);
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
     * 统一打开逻辑 —— 删除模式移除历史/全网搜/详情
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
            refreshRecentLane();
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
        if (v.getId() == R.id.tvLive) {
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
        if (tvRecentList != null) {
            tvRecentList.setAdapter(null);
            tvRecentList = null;
        }
        if (recentAdapter != null) {
            recentAdapter = null;
        }
        homeHotVodAdapter = null;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
    }
}
