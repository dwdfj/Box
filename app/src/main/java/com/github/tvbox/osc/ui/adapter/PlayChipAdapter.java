package com.github.tvbox.osc.ui.adapter;

import androidx.annotation.NonNull;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;

import java.util.List;

/**
 * 小贾影视仓 v23.3: 播放页「线路 / 选集」胶囊适配器。
 * 与竖屏播放信息区配套(见 activity_play.xml 的 play_line_list / play_episode_list)。
 *
 * v26.8: 选集从「2 行横滑」改成「4 列等宽网格」, 因此多了 gridMode:
 *   - 线路仍用 item_play_chip(wrap_content), 只有文字色区分选中;
 *   - 选集用 item_play_episode(match_parent), 选中项换成实心底 + 深色字,
 *     因为网格里只靠文字深浅根本看不出"当前播到哪一集"。
 */
public class PlayChipAdapter extends BaseQuickAdapter<String, BaseViewHolder> {

    private int selectedIndex = -1;
    private final boolean gridMode;

    public PlayChipAdapter() {
        this(R.layout.item_play_chip, false);
    }

    public PlayChipAdapter(int layoutResId, boolean gridMode) {
        super(layoutResId);
        this.gridMode = gridMode;
    }

    public void setSelectedIndex(int idx) {
        int old = this.selectedIndex;
        this.selectedIndex = idx;
        if (old >= 0 && old < getData().size()) notifyItemChanged(old);
        if (idx >= 0 && idx < getData().size()) notifyItemChanged(idx);
    }

    @Override
    protected void convert(@NonNull BaseViewHolder helper, String item) {
        helper.setText(R.id.tv_chip, item);
        // 注: 本工程用的是 BaseRecyclerViewAdapterHelper 2.9.49, 该版本没有
        // getBindingAdapterPosition()（3.x 才引入), 用 getAdapterPosition()。
        int pos = helper.getAdapterPosition();
        boolean sel = pos >= 0 && pos == selectedIndex;
        if (gridMode) {
            helper.setBackgroundRes(R.id.tv_chip,
                    sel ? R.drawable.shape_play_chip_selected : R.drawable.shape_play_chip_normal);
            helper.setTextColor(R.id.tv_chip, sel ? 0xFF17181C : 0xE6FFFFFF);
        } else {
            // v27.28: 线路胶囊的"当前线路"也用紫底(sel_play_line_chip 的 state_selected),
            // 焦点移到别处时仍能看出正在走哪条线路。
            helper.itemView.setSelected(sel);
            helper.setTextColor(R.id.tv_chip, sel ? 0xFFFFFFFF : 0xB3FFFFFF);
        }
    }
}
