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

    // v15.5 大图焦点 UI
    private ImageView bgFocus;
    private TextView tvModeTag;
    private TextView tvFocusTitle;
    private TextView tvFocusMeta;
    private TextView tvFocusDesc;
    private TextView tvFocusPlay;
    private TextView tvFocusDetail;
    private Movie.Video curFocusVod = null;
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

        super.onFragmentResume();
        if (Hawk.get(HawkConfig.HOME_REC, 0) == 2) {
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

        // v15.5 大图焦点: 背景 + 浮层
        bgFocus = findViewById(R.id.bgFocus);
        bgFocus.setImageDrawable(new ColorDrawable(0xFF0A0B0E));
        tvModeTag = findViewById(R.id.tvModeTag);
        tvFocusTitle = findViewById(R.id.tvFocusTitle);
        tvFocusMeta = findViewById(R.id.tvFocusMeta);
        tvFocusDesc = findViewById(R.id.tvFocusDesc);
        tvFocusPlay = findViewById(R.id.tvFocusPlay);
        tvFocusDetail = findViewById(R.id.tvFocusDetail);
        tvFocusPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openVod(curFocusVod);
            }
        });
        tvFocusDetail.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openVod(curFocusVod);
            }
        });

        tvHotListForLine = findViewById(R.id.tvHotListForLine);
        tvHotListForGrid = findViewById(R.id.tvHotListForGrid);
        // v15.5: 首页固定「大图焦点」—— 卡带横向滑动, 旧"单行"容器恒隐藏
        tvHotListForLine.setVisibility(View.GONE);
        tvHotListForGrid.setVisibility(View.VISIBLE);
        tvHotListForGrid.setHasFixedSize(true);
        tvHotListForGrid.setLayoutManager(new V7LinearLayoutManager(this.mContext, 0, false));
        tvHotListForGrid.setSpacingWithMargins(0, AutoSizeUtils.dp2px(this.mContext, 14.0f));

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

        // v15.5: 卡带焦点联动 —— 左右切换即时刷新背景大图与信息浮层
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
                    itemView.animate().scaleX(1.16f).scaleY(1.16f).setDuration(220).setInterpolator(new DecelerateInterpolator()).start();
                }
                focusPos = position;
                Movie.Video vod = homeHotVodAdapter.getItem(position);
                if (vod != null) updateFocusCard(vod);
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {

            }
        });
        tvHotListForGrid.setAdapter(homeHotVodAdapter);

        // 数据就绪后自动点亮第一张焦点卡(含豆瓣热播异步回调/历史模式刷新)
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
                        Movie.Video vod = homeHotVodAdapter.getItem(pos);
                        if (vod != null) updateFocusCard(vod);
                    }
                });
            }
        });

        initHomeHotVod(homeHotVodAdapter);
    }

    // v15.5: 刷新左下焦点卡信息 —— 标题/元信息/简介 + 背景大图
    private void updateFocusCard(Movie.Video vod) {
        if (vod == null) return;
        curFocusVod = vod;
        tvFocusTitle.setText(TextUtils.isEmpty(vod.name) ? "未知片名" : vod.name.trim());
        String meta = buildMeta(vod);
        if (TextUtils.isEmpty(meta)) {
            tvFocusMeta.setVisibility(View.GONE);
        } else {
            tvFocusMeta.setText(meta);
            tvFocusMeta.setVisibility(View.VISIBLE);
        }
        if (!TextUtils.isEmpty(vod.des)) {
            tvFocusDesc.setText(vod.des.trim());
            tvFocusDesc.setVisibility(View.VISIBLE);
        } else {
            tvFocusDesc.setVisibility(View.GONE);
        }
        if (!TextUtils.isEmpty(vod.pic)) {
            ImgUtil.load(vod.pic.trim(), bgFocus, 0);
        } else {
            bgFocus.setImageDrawable(new ColorDrawable(0xFF0A0B0E));
        }
    }

    // v15.5: 组装焦点卡元信息(评分/年份/类型/地区/语言/备注), 无内容返回 null
    private String buildMeta(Movie.Video vod) {
        if (vod == null) return null;
        ArrayList<String> parts = new ArrayList<>();
        if (Hawk.get(HawkConfig.HOME_REC, 0) == 0) {
            // 豆瓣热播: note = 评分
            if (!TextUtils.isEmpty(vod.note)) parts.add("豆瓣 " + vod.note.trim() + " 分");
            return parts.isEmpty() ? null : TextUtils.join("  ·  ", parts);
        }
        if (Hawk.get(HawkConfig.HOME_REC, 0) == 2) {
            if (!TextUtils.isEmpty(vod.note)) parts.add(vod.note.trim());   // "上次看到…"
            return parts.isEmpty() ? null : TextUtils.join("  ·  ", parts);
        }
        if (vod.year > 0) parts.add(String.valueOf(vod.year));
        if (!TextUtils.isEmpty(vod.type)) parts.add(vod.type.trim());
        if (!TextUtils.isEmpty(vod.area)) parts.add(vod.area.trim());
        if (!TextUtils.isEmpty(vod.lang)) parts.add(vod.lang.trim());
        if (!TextUtils.isEmpty(vod.note)) parts.add(vod.note.trim());
        return parts.isEmpty() ? null : TextUtils.join("  ·  ", parts);
    }

    // v15.5: 统一打开逻辑 —— 删除模式/全网搜/详情, 与旧 onItemClick 语义一致
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
    public void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
    }
}
