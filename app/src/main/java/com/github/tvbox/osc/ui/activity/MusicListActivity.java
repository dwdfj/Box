package com.github.tvbox.osc.ui.activity;

import android.graphics.Color;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
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
 * 小贾影视仓 v27.5: 歌单/榜单详情页(Voyage 式双栏) ——
 * 左: 大封面 + 标题/描述 + 「播放全部」; 右: 歌曲列表(正在播放行金色高亮 + ▶ 标记)。
 * 搜索模式共用本页, 左栏显示当前播放。
 */
public class MusicListActivity extends BaseActivity {
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_ID = "id";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_KEYWORD = "keyword";
    public static final String MODE_SHEET = "sheet";
    public static final String MODE_SEARCH = "search";

    private static final int COLOR_GOLD = 0xFFFFD54F;

    private final List<MusicApi.Song> songs = new ArrayList<>();
    private SongAdapter adapter;
    private TextView titleView, statusView, sourceBtn, paneTitle, paneDesc;
    private ImageView paneCover;
    private BarBind bar;

    /** v27.8: 页面自身监听器, 取链/播放/错误全部上屏。 */
    private final MusicPlayer.Listener pageListener = new MusicPlayer.Listener() {
        @Override public void onStatus(String s) { setStatus(s); }
        @Override public void onPlaying(MusicApi.Song s, String src, String q) { setStatus("▶ " + s.name + " · " + src + " · " + q); adapter.notifyDataSetChanged(); }
        @Override public void onPaused() { setStatus("已暂停"); }
        @Override public void onError(String s) { setStatus(s); }
    };

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_music_list;
    }

    @Override
    protected void init() {
        MusicPlayer.get().addListener(pageListener);
        titleView = findViewById(R.id.musicListTitle);
        statusView = findViewById(R.id.musicListStatus);
        sourceBtn = findViewById(R.id.musicListSource);
        paneCover = findViewById(R.id.musicPaneCover);
        paneTitle = findViewById(R.id.musicPaneTitle);
        paneDesc = findViewById(R.id.musicPaneDesc);
        RecyclerView list = findViewById(R.id.musicSongList);
        adapter = new SongAdapter();
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        findViewById(R.id.musicListBack).setOnClickListener(v -> finish());
        sourceBtn.setOnClickListener(v -> {
            String name = MusicPlayer.get().nextSource();
            sourceBtn.setText("源"); // v27.8: 圆形按钮只放图标字, 源名进状态行
            setStatus("已切换音源: " + name + "，重新点歌即可");
        });
        findViewById(R.id.musicPlayAll).setOnClickListener(v -> {
            if (!songs.isEmpty()) MusicPlayer.get().play(songs.get(0));
        });

        bar = new BarBind(
                findViewById(R.id.musicPlayerBar),
                findViewById(R.id.musicNowCover),
                findViewById(R.id.musicNowName),
                findViewById(R.id.musicNowInfo),
                findViewById(R.id.musicPlayPause),
                findViewById(R.id.musicProgress));
        bar.bind(this);

        String mode = getIntent().getStringExtra(EXTRA_MODE);
        if (MODE_SEARCH.equals(mode)) {
            String kw = getIntent().getStringExtra(EXTRA_KEYWORD);
            titleView.setText("搜索: " + kw);
            paneTitle.setText("搜索: " + kw);
            paneDesc.setText("在右侧列表点歌即播");
            doSearch(kw);
        } else {
            String title = getIntent().getStringExtra(EXTRA_TITLE);
            titleView.setText(title == null ? "歌单" : title);
            paneTitle.setText(title == null ? "歌单" : title);
            String id = getIntent().getStringExtra(EXTRA_ID);
            setStatus("加载歌单…");
            paneDesc.setText("加载中…");
            MusicApi.sheetDetail(id, (err, data) -> {
                songs.clear();
                if (data != null) songs.addAll(data);
                adapter.notifyDataSetChanged();
                ((android.view.ViewGroup) findViewById(R.id.musicSongList)).scheduleLayoutAnimation();
                paneDesc.setText(err != null ? String.valueOf(err)
                        : (songs.isEmpty() ? "歌单为空" : "共 " + songs.size() + " 首 · 点歌即播"));
                if (!songs.isEmpty()) {
                    String cover = songs.get(0).cover;
                    if (cover != null && !cover.isEmpty()) {
                        Glide.with(mContext).load(cover).into(paneCover);
                    }
                }
                setStatus(err != null ? "加载失败: " + err
                        : (songs.isEmpty() ? "歌单为空" : songs.size() + " 首 · 播放全部或点歌"));
            });
        }
        sourceBtn.post(() -> sourceBtn.setText("源"));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bar != null) bar.unbind();
        MusicPlayer.get().removeListener(pageListener);
    }

    private void doSearch(String kw) {
        setStatus("搜索中…");
        MusicApi.search(kw, 1, (err, data) -> {
            songs.clear();
            if (data != null) songs.addAll(data);
            adapter.notifyDataSetChanged();
            ((android.view.ViewGroup) findViewById(R.id.musicSongList)).scheduleLayoutAnimation();
            setStatus(err != null ? "搜索失败: " + err
                    : (songs.isEmpty() ? "无结果" : songs.size() + " 首 · 点歌即播"));
        });
    }

    private void setStatus(String s) {
        statusView.setText(s);
    }

    private class SongAdapter extends RecyclerView.Adapter<SongAdapter.Holder> {

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
                    if (pos < 0 || pos >= songs.size()) return;
                    MusicPlayer.get().play(songs.get(pos));
                    notifyDataSetChanged(); // 刷新"正在播放"高亮
                });
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            return new Holder(getLayoutInflater().inflate(R.layout.item_music_song, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            MusicApi.Song s = songs.get(position);
            MusicApi.Song cur = MusicPlayer.get().current();
            boolean now = cur != null && cur.songmid != null && cur.songmid.equals(s.songmid);
            // v27.9 喵音乐式: 编号列; 正在播放行用金色 ▶ 替代编号
            h.index.setText(now ? "▶" : String.valueOf(position + 1));
            h.index.setTextColor(now ? COLOR_GOLD : 0x66FFFFFF);
            h.name.setText(s.name);
            h.name.setTextColor(now ? COLOR_GOLD : Color.WHITE);
            h.singer.setText(s.singer + (s.album.isEmpty() ? "" : " · " + s.album));
            h.duration.setText(s.durationMs > 0 ? formatMs(s.durationMs) : "");
        }

        @Override
        public int getItemCount() {
            return songs.size();
        }
    }

    private static String formatMs(long ms) {
        long sec = ms / 1000;
        return String.format("%02d:%02d", sec / 60, sec % 60);
    }
}
