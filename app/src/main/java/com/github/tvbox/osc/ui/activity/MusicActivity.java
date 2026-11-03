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

    private static final String[] WEEK = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};

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
    private RowsAdapter rowsAdapter;
    private TextView statusView, sourceBtn, tabHome, tabMine;
    private BarBind bar;
    private boolean mineMode = false;

    /** v27.8: 页面自身监听器 —— 取链/播放/错误全部上屏。 */
    private final MusicPlayer.Listener pageListener = new MusicPlayer.Listener() {
        @Override public void onStatus(String s) { setStatus(s); }
        @Override public void onPlaying(MusicApi.Song s, String src, String q) { setStatus("▶ " + s.name + " · " + src + " · " + q); }
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

        TextView greetSub = findViewById(R.id.musicGreetSub);
        TextView greet = findViewById(R.id.musicPaneTitle);
        Calendar c = Calendar.getInstance();
        greetSub.setText((c.get(Calendar.MONTH) + 1) + "月" + c.get(Calendar.DAY_OF_MONTH) + "日 " + WEEK[c.get(Calendar.DAY_OF_WEEK) - 1]);
        int h = c.get(Calendar.HOUR_OF_DAY);
        greet.setText(h < 5 ? "夜深了" : h < 12 ? "早上好" : h < 18 ? "下午好" : "晚上好");

        RecyclerView rv = findViewById(R.id.musicRows);
        rowsAdapter = new RowsAdapter();
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(rowsAdapter);

        findViewById(R.id.musicBack).setOnClickListener(v -> finish());
        // v27.15: 线路胶囊旁的音乐胶囊 —— 已在音乐首页, 点它回到「首页」tab 并聚焦内容区
        findViewById(R.id.musicEnterBtn).setOnClickListener(v -> {
            setMode(false);
            RecyclerView rowsRv = findViewById(R.id.musicRows);
            rowsRv.requestFocus();
            if (rowsAdapter != null && rowsAdapter.getItemCount() > 0) rowsRv.scrollToPosition(0);
        });
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
        if (content != null) content.setPadding(
                content.getPaddingLeft(), statusH,
                content.getPaddingRight(), content.getPaddingBottom());
        View bottom = findViewById(R.id.musicBottomArea);
        if (bottom != null) bottom.setPadding(
                bottom.getPaddingLeft(), bottom.getPaddingTop(),
                bottom.getPaddingRight(),
                navH + getResources().getDimensionPixelSize(R.dimen.vs_20));
    }

    private void setMode(boolean mine) {
        if (mineMode == mine) return;
        mineMode = mine;
        tabHome.setBackgroundResource(mine ? android.R.color.transparent : R.drawable.bg_music_accent_btn);
        tabMine.setBackgroundResource(mine ? R.drawable.bg_music_accent_btn : android.R.color.transparent);
        tabMine.setTextColor(getResources().getColor(mine ? R.color.color_FFFFFF : R.color.music_sub));
        tabHome.setTextColor(getResources().getColor(mine ? R.color.music_sub : R.color.color_FFFFFF));
        rebuildRows();
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
            MusicApi.hotSheets("全部", (err2, data2) -> {
                if (data2 != null && !data2.isEmpty()) {
                    hotSheetCache.clear();
                    hotSheetCache.addAll(data2);
                }
                rebuildRows();
                setStatus(topListCache.isEmpty() && hotSheetCache.isEmpty()
                        ? "加载失败: " + (err != null ? err : err2)
                        : "上下键选行, 左右键选封面, OK 进入");
            });
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
        } else {
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
                rows.add(bannerRow());
                rows.add(sectionRow("官方榜单", topListCache));
            }
            if (!hotSheetCache.isEmpty()) rows.add(sectionRow("精品歌单", hotSheetCache));
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
            else ((SectionRowHolder) vh).bind(r);
        }

        @Override
        public int getItemCount() {
            return rows.size();
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
        final TextView week, day, title, desc, play;

        DailyHolder(View v) {
            super(v);
            week = v.findViewById(R.id.dailyWeek);
            day = v.findViewById(R.id.dailyDay);
            title = v.findViewById(R.id.dailyTitle);
            desc = v.findViewById(R.id.dailyDesc);
            play = v.findViewById(R.id.dailyPlay);
            FocusScale.attach(v, 1.02f);
            v.setOnClickListener(v1 -> {
                if (!topListCache.isEmpty()) openSheet(topListCache.get(0));
            });
            play.setOnClickListener(v1 -> {
                if (!topListCache.isEmpty()) openSheet(topListCache.get(0));
            });
        }

        void bind() {
            Calendar c = Calendar.getInstance();
            week.setText(WEEK[c.get(Calendar.DAY_OF_WEEK) - 1]);
            day.setText(String.valueOf(c.get(Calendar.DAY_OF_MONTH)));
            if (!topListCache.isEmpty()) {
                MusicApi.Sheet s = topListCache.get(0);
                title.setText("每日推荐 · " + s.name);
                desc.setText(s.sub == null ? "为你精选" : s.sub);
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
            if (s.cover != null && !s.cover.isEmpty()) {
                Glide.with(mContext).load(s.cover).into(h.cover);
            }
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
            if (s.cover != null && !s.cover.isEmpty()) {
                Glide.with(mContext).load(s.cover).into(h.cover);
            }
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
            if (s.cover != null && !s.cover.isEmpty()) {
                Glide.with(mContext).load(s.cover).into(h.cover);
            } else {
                h.cover.setImageResource(android.R.drawable.ic_media_play);
            }
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
