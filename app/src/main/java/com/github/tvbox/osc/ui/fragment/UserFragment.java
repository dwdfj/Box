package com.github.tvbox.osc.ui.fragment;

import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
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
import com.github.tvbox.osc.ui.adapter.HomeHotVodAdapter;
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
 * @description: 小贾影视仓 v15.5 —— 首页(my0页签)改为「大图焦点」模式(B方案):
 * 焦点片海报铺满上区 + 左下信息浮层(片名/简介/播放钮) + 底部横向卡带换片 + 底部快捷入口行。
 * 数据源(豆瓣热播/站点推荐/播放历史)与删除模式/长按快搜逻辑保持不变。
 */
public class UserFragment extends BaseLazyFragment implements View.OnClickListener {
    private LinearLayout tvDrive;
    private LinearLayout tvLive;
    private LinearLayout tvSearch;
    private LinearLayout tvSetting;
    private LinearLayout tvHistory;
    private LinearLayout tvCollect;
    private LinearLayout tvPush;
    public static HomeHotVodAdapter homeHotVodAdapter;
    private List<Movie.Video> homeSourceRec;
    public static TvRecyclerView tvHotListForGrid;
    public static TvRecyclerView tvHotListForLine;

    // v15.6 横屏海报墙 UI: 只剩模式标签 + 数量统计(大图焦点已废弃)
    private TextView tvModeTag;
    private TextView tvHotCount;
    private int focusPos = 0;
    private static final int HOME_SPAN = 5;

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
        // v15.6.1: view 被 ViewPager 回收后(onDestroyView 已把静态字段置空)本回调仍可能触发,
        // 此时控件引用为 null —— 只走 super(交给懒加载重建), 后续操作全部跳过, 避免 NPE。
        if (tvSearch == null || tvSetting == null) {
            return;
        }

        // takagen99: Initialize Icon Placement
        if (!Hawk.get(HawkConfig.HOME_SEARCH_POSITION, true)) {
            tvSearch.setVisibility(View.VISIBLE);
        } else {
            tvSearch.setVisibility(View.GONE);
        }
        if (!Hawk.get(HawkConfig.HOME_MENU_POSITION, true)) {
            tvSetting.setVisibility(View.VISIBLE);
        } else {
            tvSetting.setVisibility(View.GONE);
        }

        if (Hawk.get(HawkConfig.HOME_REC, 0) == 2 && homeHotVodAdapter != null) {
            List<VodInfo> allVodRecord = RoomDataManger.getAllVodRecord(20);
            List<Movie.Video> vodList = new ArrayList<>();
            for (VodInfo vodInfo : allVodRecord) {
                Movie.Video vod = new Movie.Video();
                vod.id = vodInfo.id;
                vod.sourceKey = vodInfo.sourceKey;
                vod.name = vodInfo.name;
                vod.pic = vodInfo.pic;
                vod.year = vodInfo.year;
                vod.type = vodInfo.type;
                if (vodInfo.playNote != null && !vodInfo.playNote.isEmpty())
                    vod.note = "上次看到" + vodInfo.playNote;
                vodList.add(vod);
            }
            homeHotVodAdapter.setNewData(vodList);
        }
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_user;
    }
    private ImgUtil.Style style;
    @Override
    protected void init() {
        EventBus.getDefault().register(this);
        tvDrive = findViewById(R.id.tvDrive);
        tvLive = findViewById(R.id.tvLive);
        tvSearch = findViewById(R.id.tvSearch);
        tvSetting = findViewById(R.id.tvSetting);
        tvCollect = findViewById(R.id.tvFavorite);
        tvHistory = findViewById(R.id.tvHistory);
        tvPush = findViewById(R.id.tvPush);
        tvDrive.setOnClickListener(this);
        tvLive.setOnClickListener(this);
        tvSearch.setOnClickListener(this);
        tvSetting.setOnClickListener(this);
        tvHistory.setOnClickListener(this);
        tvPush.setOnClickListener(this);
        tvCollect.setOnClickListener(this);
        tvDrive.setOnFocusChangeListener(focusChangeListener);
        tvLive.setOnFocusChangeListener(focusChangeListener);
        tvSearch.setOnFocusChangeListener(focusChangeListener);
        tvSetting.setOnFocusChangeListener(focusChangeListener);
        tvHistory.setOnFocusChangeListener(focusChangeListener);
        tvPush.setOnFocusChangeListener(focusChangeListener);
        tvCollect.setOnFocusChangeListener(focusChangeListener);

        // v15.6 横屏海报墙: 顶部只保留模式标签 + 数量统计
        tvModeTag = findViewById(R.id.tvModeTag);
        tvHotCount = findViewById(R.id.tvHotCount);

        tvHotListForLine = findViewById(R.id.tvHotListForLine);
        tvHotListForGrid = findViewById(R.id.tvHotListForGrid);
        // v15.6: 首页固定「横屏海报墙」—— 网格排布, 旧"单行"容器恒隐藏
        tvHotListForLine.setVisibility(View.GONE);
        tvHotListForGrid.setVisibility(View.VISIBLE);
        tvHotListForGrid.setHasFixedSize(true);
        tvHotListForGrid.setLayoutManager(new V7GridLayoutManager(this.mContext, HOME_SPAN));
        tvHotListForGrid.setSpacingWithMargins(AutoSizeUtils.dp2px(this.mContext, 12.0f), AutoSizeUtils.dp2px(this.mContext, 12.0f));

        String tvRate = "";
        if (Hawk.get(HawkConfig.HOME_REC, 0) == 0) {
            tvRate = "豆瓣热播";
            tvModeTag.setText(getString(R.string.hm_mode_douban));
        } else if (Hawk.get(HawkConfig.HOME_REC, 0) == 1) {
            tvRate = homeSourceRec != null ? "站点推荐" : "豆瓣热播";
            tvModeTag.setText(getString(R.string.hm_mode_site));
        } else if (Hawk.get(HawkConfig.HOME_REC, 0) == 2) {
            tvRate = "播放历史";
            tvModeTag.setText(getString(R.string.hm_mode_history));
        }
        if (Hawk.get(HawkConfig.HOME_REC, 0) == 1 && homeSourceRec != null) {
            style = ImgUtil.initStyle();
        }
        homeHotVodAdapter = new HomeHotVodAdapter(style, tvRate);
        homeHotVodAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                if (ApiConfig.get().getSourceBeanList().isEmpty())
                    return;
                Movie.Video vod = (Movie.Video) adapter.getItem(position);
                openVod(vod);
            }
        });
        // takagen99 : Long press to trigger Delete Mode for VOD History on Home Page
        homeHotVodAdapter.setOnItemLongClickListener(new BaseQuickAdapter.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(BaseQuickAdapter adapter, View view, int position) {
                if (ApiConfig.get().getSourceBeanList().isEmpty())
                    return false;
                Movie.Video vod = (Movie.Video) adapter.getItem(position);
                // Additional Check if : Home Rec 0=豆瓣, 1=推荐, 2=历史
                if ((vod.id != null && !vod.id.isEmpty()) && (Hawk.get(HawkConfig.HOME_REC, 0) == 2)) {
                    HawkConfig.hotVodDelete = !HawkConfig.hotVodDelete;
                    homeHotVodAdapter.notifyDataSetChanged();
                } else {
                    Intent newIntent = new Intent(mContext, FastSearchActivity.class);
                    newIntent.putExtra("title", vod.name);
                    newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    mActivity.startActivity(newIntent);
                }
                return true;
            }
        });

        tvHistory.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                HomeActivity.homeRecf();
                return HomeActivity.reHome(mContext);
            }
        });

        // v15.6: 网格焦点 —— 轻微放大避免与邻卡重叠, 并同步更新"第几个/共几个"
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
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {

            }
        });
        tvHotListForGrid.setAdapter(homeHotVodAdapter);

        // 数据就绪后刷新数量统计(含豆瓣热播异步回调/历史模式刷新)
        homeHotVodAdapter.registerAdapterDataObserver(new androidx.recyclerview.widget.RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                if (homeHotVodAdapter.getData().isEmpty()) return;
                tvHotListForGrid.post(new Runnable() {
                    @Override
                    public void run() {
                        if (homeHotVodAdapter.getData().isEmpty()) return;
                        int pos = Math.min(focusPos, homeHotVodAdapter.getData().size() - 1);
                        if (pos < 0) pos = 0;
                        updateCount(pos);
                    }
                });
            }
        });

        initHomeHotVod(homeHotVodAdapter);
    }

    // v15.6: 顶部数量统计 —— "第 N 个 / 共 M 个"
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

    // v15.6: 统一打开逻辑 —— 删除模式/全网搜/详情, 与旧 onItemClick 语义一致
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

    private void initHomeHotVod(HomeHotVodAdapter adapter) {
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
        } else if (v.getId() == R.id.tvSearch) {
            jumpActivity(SearchActivity.class);
        } else if (v.getId() == R.id.tvSetting) {
            jumpActivity(SettingActivity.class);
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
        // v15.6.1: tvHotListForGrid/tvHotListForLine 是静态字段, 若不置空会一直钉住已销毁的 RecyclerView,
        // 连同其 Context(Activity) 一起泄漏 —— 每次切线路重建 fragments 就多漏一份。
        // HomeActivity 访问这两处均已判空(onBackPressed), 置空安全。
        if (tvHotListForGrid != null) {
            tvHotListForGrid.setAdapter(null);
            tvHotListForGrid = null;
        }
        if (tvHotListForLine != null) {
            tvHotListForLine.setAdapter(null);
            tvHotListForLine = null;
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
