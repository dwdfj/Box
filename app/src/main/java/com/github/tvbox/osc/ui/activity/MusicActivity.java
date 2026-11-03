package com.github.tvbox.osc.ui.activity;

import android.content.Intent;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.music.BarBind;
import com.github.tvbox.osc.music.FocusScale;
import com.github.tvbox.osc.music.MusicApi;
import com.github.tvbox.osc.music.MusicPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * 小贾影视仓 v27.7: 音乐厅(Voyage ListDetail 双栏 IA) ——
 * 左: 导航轨(音乐馆/排行榜/精品歌单, 对齐 Voyage MusicLibraryRoute/MusicChartsRoute/MusicPlaylistsRoute);
 * 右: 标题+搜索+Banner 横滑大图+分区横向封面墙。
 * 播放条: 封面旋转 + 粉紫细进度条(BarBind 驱动)。
 */
public class MusicActivity extends BaseActivity {
    private static final int TYPE_BANNER_ROW = 0;
    private static final int TYPE_SECTION_ROW = 1;

    /** 外层行: banner 模式=整行大图; section 模式=标题+横向封面列表。 */
    private static class Row {
        int type;
        String sectionTitle;
        List<MusicApi.Sheet> sheets;
    }

    private final List<Row> rows = new ArrayList<>();
    private final List<MusicApi.Sheet> banners = new ArrayList<>();
    private final List<MusicApi.Sheet> topListCache = new ArrayList<>();
    private final List<MusicApi.Sheet> hotSheetCache = new ArrayList<>();
    private RowsAdapter rowsAdapter;
    private TextView statusView, sourceBtn;
    private BarBind bar;

    /** v27.8: 页面自身监听器 —— 取链/播放/错误全部上屏(此前被 BarBind 独占, 点击无任何反馈)。 */
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

    @Override
    protected void init() {
        MusicPlayer.get().addListener(pageListener);
        statusView = findViewById(R.id.musicStatus);
        sourceBtn = findViewById(R.id.musicSourceBtn);

        RecyclerView rv = findViewById(R.id.musicRows);
        rowsAdapter = new RowsAdapter();
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(rowsAdapter);

        findViewById(R.id.musicBack).setOnClickListener(v -> finish());
        sourceBtn.setOnClickListener(v -> {
            // v27.10: 「音源」按钮 = 音质偏好循环(flac/320k/128k)
            String q = MusicPlayer.get().cycleQuality();
            sourceBtn.setText(q);
            setStatus("音质偏好已切至 " + q + "，下次点歌生效");
        });
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
                findViewById(R.id.musicPlayPause),
                findViewById(R.id.musicProgress));
        bar.bind(this);

        sourceBtn.setText(MusicPlayer.get().preferredQuality());
        loadHall();
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

    /** v27.10: 单首页(照喵音乐) —— Banner + 官方榜单 + 精品歌单, 无标签页。 */
    private void rebuildRows() {
        rows.clear();
        banners.clear();
        if (!topListCache.isEmpty()) {
            banners.addAll(topListCache.subList(0, Math.min(4, topListCache.size())));
            rows.add(bannerRow());
            rows.add(sectionRow("官方榜单", topListCache));
        }
        if (!hotSheetCache.isEmpty()) rows.add(sectionRow("精品歌单", hotSheetCache));
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
            if (viewType == TYPE_BANNER_ROW) {
                return new BannerRowHolder(inf.inflate(R.layout.item_music_banner_row, parent, false));
            }
            return new SectionRowHolder(inf.inflate(R.layout.item_music_row, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int position) {
            Row r = rows.get(position);
            if (vh instanceof BannerRowHolder) {
                ((BannerRowHolder) vh).bind();
            } else {
                ((SectionRowHolder) vh).bind(r);
            }
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
            adapter.notifyDataSetChanged();
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
}
