package com.github.tvbox.osc.ui.activity;

import android.content.Intent;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.music.BarBind;
import com.github.tvbox.osc.music.BlobPlayButton;
import com.github.tvbox.osc.music.FocusScale;
import com.github.tvbox.osc.music.MusicApi;
import com.github.tvbox.osc.music.MusicPlayer;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 小贾影视仓 v27.12: 音乐首页(Voyage 工具箱式) ——
 * 问候语 + 大搜索胶囊 + 每日推荐卡 + 最近播放 + 官方榜单 + 精品歌单;
 * 底部「首页/我的」悬浮胶囊 + 灵动条(Blob 液态播放钮 + 环形进度)。
 */
public class MusicActivity extends BaseActivity {
    private static final int TYPE_BANNER_ROW = 0;
    private static final int TYPE_SECTION_ROW = 1;
    private static final int TYPE_DAILY_ROW = 2;
    private static final int TYPE_RECENT_ROW = 3;
    private static final int TYPE_SONG_ROW = 4; // 我的 tab: 竖版最近播放列表
    private static final int TYPE_QR_ROW = 5;   // 我的 tab: 底部小程序码卡片

    private static final String[] WEEK = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};

    /**
     * v27.19: 首页大标题改成「每天一句简短问候」——
     * 按 年积日 % 4 取一条, 同一天固定、隔天必换; 每段时段各备 4 句, 都是 3 个字,
     * 不会因为字数变多把右侧线路胶囊挤出去。
     */
    private static final String[][] GREET_POOL = {
            {"夜深了", "还没睡", "夜猫子", "早点睡"},   // 00:00 - 05:00
            {"早上好", "新一天", "早安呀", "今天好"},   // 05:00 - 12:00
            {"下午好", "午后好", "歇一会", "午安啦"},   // 12:00 - 18:00
            {"晚上好", "晚风起", "放松下", "夜听时"}    // 18:00 - 24:00
    };

    /** 按日期挑当日问候语 —— 同一天刷新不变, 隔天自动换。 */
    private static String dailyGreet(int hour, Calendar c) {
        int slot = hour < 5 ? 0 : hour < 12 ? 1 : hour < 18 ? 2 : 3;
        int dayOfYear = c.get(Calendar.DAY_OF_YEAR);
        String[] pool = GREET_POOL[slot];
        return pool[dayOfYear % pool.length];
    }

    private static class Row {
        int type;
        String sectionTitle;
        List<MusicApi.Sheet> sheets;
        List<MusicApi.Song> songs;
    }

    private final List<Row> rows = new ArrayList<>();
    private final List<MusicApi.Sheet> banners = new ArrayList<>();
    private final List<MusicApi.Sheet> topListCache = new ArrayList<>();
    private final List<MusicApi.Sheet> hotSheetCache = new ArrayList<>();
    /** v27.18: 首页「推荐歌曲」区 —— 取每日推荐第一张榜单里的前几首, 用来填满下方留白。 */
    private final List<MusicApi.Song> dailySongs = new ArrayList<>();
    private RowsAdapter rowsAdapter;
    private TextView statusView, sourceBtn;
    /** v27.25: dock 两个图标钮(苹果 dock 形态, 无文字标签)。 */
    private android.widget.ImageView tabHome, tabMine;
    private BarBind bar;
    private boolean mineMode = false;

    /** v27.8: 页面自身监听器 —— 取链/播放/错误全部上屏。 */
    private final MusicPlayer.Listener pageListener = new MusicPlayer.Listener() {
        @Override public void onStatus(String s) { setStatus(s); }
        @Override public void onPlaying(MusicApi.Song s, String src, String q) { setStatus("▶ " + s.name + " · " + src + " · " + MusicPlayer.qualityLabel(q)); }
        @Override public void onPaused() { setStatus("已暂停"); }
        @Override public void onError(String s) { setStatus(s); }
    };

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_music;
    }

    /** v27.13: 真铺满 —— 系统栏沉浸, 窗口背景 = 内容背景。 */
    @Override
    protected boolean musicFullscreen() {
        return true;
    }

    /** 系统栏高度(px), 用于内容避让但背景仍铺到屏幕边缘。 */
    static int sysBarHeight(android.content.Context ctx, boolean statusBar) {
        int id = ctx.getResources().getIdentifier(
                statusBar ? "status_bar_height" : "navigation_bar_height", "dimen", "android");
        return id > 0 ? ctx.getResources().getDimensionPixelSize(id) : 0;
    }

    @Override
    protected void init() {
        applySysBarPadding();
        MusicPlayer.get().addListener(pageListener);
        statusView = findViewById(R.id.musicStatus);
        sourceBtn = findViewById(R.id.musicSourceBtn);
        tabHome = findViewById(R.id.musicTabHome);
        tabMine = findViewById(R.id.musicTabMine);
        // v27.25: dock 用 selector 画选中态, 初始「首页」为选中
        tabHome.setSelected(true);

        TextView greetSub = findViewById(R.id.musicGreetSub);
        TextView greet = findViewById(R.id.musicPaneTitle);
        Calendar c = Calendar.getInstance();
        greetSubText = (c.get(Calendar.MONTH) + 1) + "月" + c.get(Calendar.DAY_OF_MONTH) + "日 " + WEEK[c.get(Calendar.DAY_OF_WEEK) - 1];
        greetSub.setText(greetSubText);
        int h = c.get(Calendar.HOUR_OF_DAY);
        // v27.19: 大标题不再是固定的「早上好/晚上好」, 改成每天一换的简短问候
        greetTitle = dailyGreet(h, c);
        greet.setText(greetTitle);

        RecyclerView rv = findViewById(R.id.musicRows);
        rowsAdapter = new RowsAdapter();
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(rowsAdapter);

        findViewById(R.id.musicBack).setOnClickListener(v -> finish());
        sourceBtn.setOnClickListener(v -> LineDialog.show(this, name -> {
            sourceBtn.setText(name);
            setStatus("线路已切到 " + name);
        }));
        tabHome.setOnClickListener(v -> setMode(false));
        tabMine.setOnClickListener(v -> setMode(true));

        findViewById(R.id.musicSearchBtn).setOnClickListener(v -> doSearch());
        EditText input = findViewById(R.id.musicSearchInput);
        input.setOnEditorActionListener((v, actionId, event) -> {
            doSearch();
            return true;
        });

        bar = new BarBind(
                findViewById(R.id.musicPlayerBar),
                findViewById(R.id.musicNowCover),
                findViewById(R.id.musicNowName),
                findViewById(R.id.musicNowInfo),
                (BlobPlayButton) findViewById(R.id.musicBlobPlay),
                findViewById(R.id.musicNextBtn),
                (com.github.tvbox.osc.music.CoverRingView) findViewById(R.id.musicNowRing));
        bar.bind(this);
        // v27.24: 灵动条音质胶囊 —— 显示当前档位, 点开改档并立即用新档重取当前曲
        TextView qualityChip = findViewById(R.id.musicPlayerQuality);
        if (qualityChip != null) {
            bar.setQualityChip(qualityChip);
            qualityChip.setOnClickListener(v -> com.github.tvbox.osc.music.MusicQualityDialog.show(this, () -> {
                String q = MusicPlayer.get().preferredQuality();
                qualityChip.setText(MusicPlayer.qualityLabel(q));
                // v27.25: 选到当前线路没有的档位时会自动换线路, 顶部胶囊要跟着变
                sourceBtn.setText(MusicPlayer.get().lineName());
                MusicApi.Song cur = MusicPlayer.get().current();
                if (cur != null) {
                    MusicPlayer.get().playQueue(MusicPlayer.get().queue(),
                            Math.max(0, MusicPlayer.get().queue().indexOf(cur)));
                    setStatus("音质切至 " + MusicPlayer.qualityLabel(q) + "（" + MusicPlayer.get().lineName() + "），正在重取");
                } else {
                    setStatus("音质偏好已切至 " + MusicPlayer.qualityLabel(q));
                }
            }));
        }
        sourceBtn.setText(MusicPlayer.get().lineName());
        loadHall();
    }

    /**
     * v27.13: 让背景铺满全屏(含系统栏区域), 但可点击内容避让状态栏与导航栏。
     * 直接给根 FrameLayout 加 padding 会把背景也缩进去, 所以 padding 加在两层子容器上:
     * 上部主内容补状态栏高度, 底部悬浮区补导航栏高度。
     */
    private void applySysBarPadding() {
        int statusH = sysBarHeight(this, true);
        int navH = sysBarHeight(this, false);
        View content = findViewById(R.id.musicContent);
        if (content != null) {
            // v27.18 修: 原来这里直接用 statusH 覆盖了 XML 的 paddingTop —— 布局里留的
            // vs_96 呼吸区被吃掉, 结果就是「顶栏贴着状态栏、返回键显得很小」。
            // 正确做法是「状态栏高度 + 布局自带留白」相加。
            int baseTop = content.getPaddingTop();
            content.setPadding(
                    content.getPaddingLeft(), statusH + baseTop,
                    content.getPaddingRight(), content.getPaddingBottom());
        }
        View bottom = findViewById(R.id.musicBottomArea);
        if (bottom != null) {
            // v27.24: 同样要「布局原留白 + 导航栏高度」相加 —— 原来直接写 vs_32,
            // 把 XML 里越改越大的离底距离又顶回去了, 所以用户怎么调都觉得 dock 贴着底边。
            int baseBottom = bottom.getPaddingBottom();
            bottom.setPadding(
                    bottom.getPaddingLeft(), bottom.getPaddingTop(),
                    bottom.getPaddingRight(), navH + baseBottom);
        }
    }

    private void setMode(boolean mine) {
        if (mineMode == mine) return;
        mineMode = mine;
        // v27.25: 选中态 = 白色高亮方块 + 纯白图标(背景/着色都走 selector, 不再手动换 drawable)
        tabHome.setSelected(!mine);
        tabMine.setSelected(mine);
        // v27.16: 「我的」不是副本首页 —— 标题与状态行跟着换, 否则两个 tab 长得一样分不清
        TextView title = findViewById(R.id.musicPaneTitle);
        if (title != null) title.setText(mine ? "我的" : greetTitle);
        TextView sub = findViewById(R.id.musicGreetSub);
        if (sub != null) {
            sub.setText(mine ? "收藏 · 最近播放" : greetSubText);
        }
        setStatus(mine ? "我的音乐库" : "为你推荐");
        rebuildRows();
    }

    /** 「首页」模式下的大标题与副标题(切到「我的」后要能切回来)。 */
    private String greetTitle = "晚上好";
    private String greetSubText = "";

    @Override
    protected void onResume() {
        super.onResume();
        // v27.26: 从影视页/播放页回到音乐页时, 灵动条的播放键要跟真实状态一致(之前是 ▶ 却没在播/在播却显示 ▶)
        if (bar != null) bar.syncPlayState();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bar != null) bar.unbind();
        MusicPlayer.get().removeListener(pageListener);
    }

    private void loadHall() {
        setStatus("正在加载…");
        MusicApi.topLists((err, data) -> {
            if (data != null && !data.isEmpty()) {
                topListCache.clear();
                topListCache.addAll(data);
            }
            rebuildRows();
            loadDailySongs();
            MusicApi.hotSheets("全部", (err2, data2) -> {
                if (data2 != null && !data2.isEmpty()) {
                    hotSheetCache.clear();
                    hotSheetCache.addAll(data2);
                }
                rebuildRows();
                // v27.16: 原来这里显示「上下键选行, 左右键选封面, OK 进入」—— TV 操作提示
                // 不该占首页, 按老大要求去掉, 状态行只在异常时提示
                setStatus(topListCache.isEmpty() && hotSheetCache.isEmpty()
                        ? "加载失败: " + (err != null ? err : err2) : "为你推荐");
            });
        });
    }

    /** v27.18: 拉每日推荐第一张榜单的歌曲 —— 卡片上的三张缩略图与「推荐歌曲」列表共用。 */
    private void loadDailySongs() {
        if (topListCache.isEmpty()) return;
        MusicApi.sheetDetail(topListCache.get(0).id, (err, data) -> {
            if (data == null || data.isEmpty()) return;
            dailySongs.clear();
            dailySongs.addAll(data.subList(0, Math.min(6, data.size())));
            rebuildRows();
        });
    }

    /** v27.12: 双模式重建 —— 首页(每日推荐+最近播放+榜单) / 我的(最近播放列表)。 */
    private void rebuildRows() {
        rows.clear();
        banners.clear();
        if (mineMode) {
            // 我的: 收藏 → 最近播放
            List<MusicApi.Song> fav = MusicPlayer.get().favorites();
            if (!fav.isEmpty()) {
                Row r = new Row();
                r.type = TYPE_SONG_ROW;
                r.sectionTitle = "我的收藏";
                r.songs = fav;
                rows.add(r);
            }
            List<MusicApi.Song> rec = MusicPlayer.get().recent();
            if (!rec.isEmpty()) {
                Row r = new Row();
                r.type = TYPE_SONG_ROW;
                r.sectionTitle = "最近播放";
                r.songs = rec;
                rows.add(r);
            }
            // v27.21: 小程序码常驻「我的」页底部, 空列表时也能看到
            Row qr = new Row();
            qr.type = TYPE_QR_ROW;
            rows.add(qr);
        } else {
            // v27.16: 首页精简 —— 每日推荐 → 最近播放 → 精品歌单。
            // 原来的「飙升榜大色块 banner」+「官方榜单」整块删掉:
            //   banner 是纯装饰, 下面官方榜单又是同一批榜单, 重复且造成视觉噪音。
            // 榜单/歌单只保留「精品歌单」一个入口, 点进去再挑。
            if (!topListCache.isEmpty()) {
                banners.addAll(topListCache.subList(0, Math.min(4, topListCache.size())));
                Row daily = new Row();
                daily.type = TYPE_DAILY_ROW;
                rows.add(daily);
                List<MusicApi.Song> rec = MusicPlayer.get().recent();
                if (!rec.isEmpty()) {
                    Row rr = new Row();
                    rr.type = TYPE_RECENT_ROW;
                    rr.songs = rec;
                    rows.add(rr);
                }
            }
            if (!hotSheetCache.isEmpty()) rows.add(sectionRow("精品歌单", hotSheetCache));
            // v27.18: 推荐歌曲 —— 补满「精品歌单」下面的空档, 也让首页有直接可点的歌
            if (!dailySongs.isEmpty()) {
                Row songs = new Row();
                songs.type = TYPE_SONG_ROW;
                songs.sectionTitle = "推荐歌曲";
                songs.songs = dailySongs;
                rows.add(songs);
            }
        }
        rowsAdapter.notifyDataSetChanged();
        if (!rows.isEmpty()) {
            ((ViewGroup) findViewById(R.id.musicRows)).scheduleLayoutAnimation();
        }
    }

    private Row bannerRow() {
        Row r = new Row();
        r.type = TYPE_BANNER_ROW;
        return r;
    }

    private Row sectionRow(String title, List<MusicApi.Sheet> sheets) {
        Row r = new Row();
        r.type = TYPE_SECTION_ROW;
        r.sectionTitle = title;
        r.sheets = sheets;
        return r;
    }

    private void openSheet(MusicApi.Sheet s) {
        Intent it = new Intent(this, MusicListActivity.class);
        it.putExtra(MusicListActivity.EXTRA_MODE, MusicListActivity.MODE_SHEET);
        it.putExtra(MusicListActivity.EXTRA_ID, s.id);
        it.putExtra(MusicListActivity.EXTRA_TITLE, s.name);
        startActivity(it);
    }

    private void doSearch() {
        EditText input = findViewById(R.id.musicSearchInput);
        String kw = input.getText().toString().trim();
        if (kw.isEmpty()) return;
        Intent it = new Intent(this, MusicListActivity.class);
        it.putExtra(MusicListActivity.EXTRA_MODE, MusicListActivity.MODE_SEARCH);
        it.putExtra(MusicListActivity.EXTRA_KEYWORD, kw);
        startActivity(it);
    }

    private void setStatus(String s) {
        statusView.setText(s);
    }

    // ---------------- 内容行 ----------------

    private class RowsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).type;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = getLayoutInflater();
            switch (viewType) {
                case TYPE_BANNER_ROW:
                    return new BannerRowHolder(inf.inflate(R.layout.item_music_banner_row, parent, false));
                case TYPE_DAILY_ROW:
                    return new DailyHolder(inf.inflate(R.layout.item_music_daily, parent, false));
                case TYPE_RECENT_ROW:
                    return new RecentRowHolder(inf.inflate(R.layout.item_music_row, parent, false));
                case TYPE_SONG_ROW:
                    return new SongListHolder(inf.inflate(R.layout.layout_music_songlist, parent, false));
                case TYPE_QR_ROW:
                    return new QrHolder(inf.inflate(R.layout.item_music_qr, parent, false));
                default:
                    return new SectionRowHolder(inf.inflate(R.layout.item_music_row, parent, false));
            }
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int position) {
            Row r = rows.get(position);
            if (vh instanceof BannerRowHolder) ((BannerRowHolder) vh).bind();
            else if (vh instanceof DailyHolder) ((DailyHolder) vh).bind();
            else if (vh instanceof RecentRowHolder) ((RecentRowHolder) vh).bind(r);
            else if (vh instanceof SongListHolder) ((SongListHolder) vh).bind(r);
            else if (vh instanceof QrHolder) { /* 静态卡片, 无需绑定 */ }
            else ((SectionRowHolder) vh).bind(r);
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }

    /** v27.21: 「我的」页底部的小程序码卡片, 纯静态。 */
    class QrHolder extends RecyclerView.ViewHolder {
        QrHolder(View v) {
            super(v);
            FocusScale.attach(v, 1.02f);
        }
    }

    class BannerRowHolder extends RecyclerView.ViewHolder {
        final BannerAdapter adapter;

        BannerRowHolder(View v) {
            super(v);
            RecyclerView list = v.findViewById(R.id.bannerList);
            list.setLayoutManager(new LinearLayoutManager(mContext, LinearLayoutManager.HORIZONTAL, false));
            new LinearSnapHelper().attachToRecyclerView(list);
            adapter = new BannerAdapter();
            list.setAdapter(adapter);
        }

        void bind() {
            if (adapter == null || banners.isEmpty()) return;
            adapter.notifyDataSetChanged();
        }
    }

    /** 每日推荐卡: 取官方榜单第一张, 点播放键直接进榜单。 */
    class DailyHolder extends RecyclerView.ViewHolder {
        final TextView title, desc, play;
        final ImageView thumb1, thumb2, thumb3;

        DailyHolder(View v) {
            super(v);
            title = v.findViewById(R.id.dailyTitle);
            desc = v.findViewById(R.id.dailyDesc);
            play = v.findViewById(R.id.dailyPlay);
            thumb1 = v.findViewById(R.id.dailyThumb1);
            thumb2 = v.findViewById(R.id.dailyThumb2);
            thumb3 = v.findViewById(R.id.dailyThumb3);
            FocusScale.attach(v, 1.02f);
            v.setOnClickListener(v1 -> {
                if (!topListCache.isEmpty()) openSheet(topListCache.get(0));
            });
            play.setOnClickListener(v1 -> {
                if (!topListCache.isEmpty()) openSheet(topListCache.get(0));
            });
        }

        void bind() {
            // v27.30: 去掉左侧「周三 / 4」日期块, 标题与说明左对齐即可
            title.setText("每日推荐");
            desc.setText(dailySongs.isEmpty() ? "每天更新" : "每天更新 · " + dailySongs.size() + " 首");
            ImageView[] thumbs = {thumb1, thumb2, thumb3};
            for (int i = 0; i < thumbs.length; i++) {
                if (i < dailySongs.size()) {
                    thumbs[i].setVisibility(View.VISIBLE);
                    com.github.tvbox.osc.music.CoverFallback.load(
                            thumbs[i], dailySongs.get(i).cover, dailySongs.get(i).name);
                } else {
                    thumbs[i].setVisibility(View.GONE);
                }
            }
        }
    }

    class SectionRowHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final SheetAdapter adapter;

        SectionRowHolder(View v) {
            super(v);
            title = v.findViewById(R.id.rowTitle);
            RecyclerView list = v.findViewById(R.id.rowList);
            list.setLayoutManager(new LinearLayoutManager(mContext, LinearLayoutManager.HORIZONTAL, false));
            adapter = new SheetAdapter();
            list.setAdapter(adapter);
        }

        void bind(Row r) {
            title.setText(r.sectionTitle);
            adapter.sheets = r.sheets;
            adapter.notifyDataSetChanged();
        }
    }

    /** 最近播放横滑行。 */
    class RecentRowHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final RecentAdapter adapter;

        RecentRowHolder(View v) {
            super(v);
            title = v.findViewById(R.id.rowTitle);
            RecyclerView list = v.findViewById(R.id.rowList);
            list.setLayoutManager(new LinearLayoutManager(mContext, LinearLayoutManager.HORIZONTAL, false));
            adapter = new RecentAdapter();
            list.setAdapter(adapter);
        }

        void bind(Row r) {
            title.setText("最近播放");
            adapter.songs = r.songs;
            adapter.notifyDataSetChanged();
        }
    }

    /** 我的 tab: 竖版歌曲列表容器。 */
    class SongListHolder extends RecyclerView.ViewHolder {
        final RecyclerView list;
        final TextView title;
        final SongAdapter adapter;

        SongListHolder(View v) {
            super(v);
            title = v.findViewById(R.id.songListTitle);
            list = v.findViewById(R.id.songList);
            list.setLayoutManager(new LinearLayoutManager(mContext));
            adapter = new SongAdapter();
            list.setAdapter(adapter);
        }

        void bind(Row r) {
            title.setText(r.sectionTitle == null ? "歌曲" : r.sectionTitle);
            adapter.songs = r.songs;
            adapter.notifyDataSetChanged();
        }
    }

    /** 横滑大图卡。 */
    private class BannerAdapter extends RecyclerView.Adapter<BannerAdapter.Holder> {

        class Holder extends RecyclerView.ViewHolder {
            final com.google.android.material.imageview.ShapeableImageView cover;
            final TextView title;
            final View ring;

            Holder(View v) {
                super(v);
                cover = v.findViewById(R.id.bannerCover);
                title = v.findViewById(R.id.bannerTitle);
                ring = v.findViewById(R.id.bannerRing);
                FocusScale.attach(v, 1.04f);
                v.setOnClickListener(v1 -> {
                    int pos = getAdapterPosition();
                    if (pos >= 0 && pos < banners.size()) openSheet(banners.get(pos));
                });
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(getLayoutInflater().inflate(R.layout.item_music_banner, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            MusicApi.Sheet s = banners.get(position);
            h.title.setText(s.name);
            com.github.tvbox.osc.music.CoverFallback.load(h.cover, s.cover, s.name);
            h.ring.setVisibility(View.INVISIBLE);
        }

        @Override
        public int getItemCount() {
            return banners.size();
        }
    }

    /** 分区内横向封面卡。 */
    private class SheetAdapter extends RecyclerView.Adapter<SheetAdapter.Holder> {
        List<MusicApi.Sheet> sheets = new ArrayList<>();

        class Holder extends RecyclerView.ViewHolder {
            final com.google.android.material.imageview.ShapeableImageView cover;
            final TextView name, sub;

            Holder(View v) {
                super(v);
                cover = v.findViewById(R.id.itemSheetCover);
                name = v.findViewById(R.id.itemSheetName);
                sub = v.findViewById(R.id.itemSheetSub);
                FocusScale.attach(v);
                v.setOnClickListener(v1 -> {
                    int pos = getAdapterPosition();
                    if (pos >= 0 && pos < sheets.size()) openSheet(sheets.get(pos));
                });
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(getLayoutInflater().inflate(R.layout.item_music_sheet, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            MusicApi.Sheet s = sheets.get(position);
            h.name.setText(s.name);
            h.sub.setText(s.sub == null ? "" : s.sub);
            com.github.tvbox.osc.music.CoverFallback.load(h.cover, s.cover, s.name);
        }

        @Override
        public int getItemCount() {
            return sheets.size();
        }
    }

    /** 最近播放横滑卡: 点歌即播(整列做队列)。 */
    private class RecentAdapter extends RecyclerView.Adapter<RecentAdapter.Holder> {
        List<MusicApi.Song> songs = new ArrayList<>();

        class Holder extends RecyclerView.ViewHolder {
            final ImageView cover;
            final TextView name, singer;

            Holder(View v) {
                super(v);
                cover = v.findViewById(R.id.itemRecentCover);
                name = v.findViewById(R.id.itemRecentName);
                singer = v.findViewById(R.id.itemRecentSinger);
                FocusScale.attach(v);
                v.setOnClickListener(v1 -> {
                    int pos = getAdapterPosition();
                    if (pos >= 0 && pos < songs.size()) MusicPlayer.get().playQueue(songs, pos);
                });
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(getLayoutInflater().inflate(R.layout.item_music_recent, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            MusicApi.Song s = songs.get(position);
            h.name.setText(s.name);
            h.singer.setText(s.singer);
            com.github.tvbox.osc.music.CoverFallback.load(h.cover, s.cover, s.name);
        }

        @Override
        public int getItemCount() {
            return songs.size();
        }
    }

    /** 我的 tab: 竖版编号歌曲列表(复用喵音乐式行)。 */
    private class SongAdapter extends RecyclerView.Adapter<SongAdapter.Holder> {
        List<MusicApi.Song> songs = new ArrayList<>();

        class Holder extends RecyclerView.ViewHolder {
            TextView index, name, singer, duration;

            Holder(View v) {
                super(v);
                index = v.findViewById(R.id.itemSongIndex);
                name = v.findViewById(R.id.itemSongName);
                singer = v.findViewById(R.id.itemSongSinger);
                duration = v.findViewById(R.id.itemSongDuration);
                FocusScale.attach(v, 1.03f);
                v.setOnClickListener(v1 -> {
                    int pos = getAdapterPosition();
                    if (pos >= 0 && pos < songs.size()) MusicPlayer.get().playQueue(songs, pos);
                });
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(getLayoutInflater().inflate(R.layout.item_music_song, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            MusicApi.Song s = songs.get(position);
            MusicApi.Song cur = MusicPlayer.get().current();
            boolean now = cur != null && cur.songmid != null && cur.songmid.equals(s.songmid);
            h.index.setText(now ? "▶" : String.valueOf(position + 1));
            h.index.setTextColor(now ? 0xFF7AA8FF : 0x66FFFFFF);
            h.name.setText(s.name);
            h.name.setTextColor(now ? 0xFF7AA8FF : Color.WHITE);
            h.singer.setText(s.singer);
            h.duration.setText("");
        }

        @Override
        public int getItemCount() {
            return songs.size();
        }
    }
}
