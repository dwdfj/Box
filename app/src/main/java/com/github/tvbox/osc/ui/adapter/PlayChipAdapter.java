package com.github.tvbox.osc.ui.adapter;

import androidx.annotation.NonNull;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;

import java.util.List;

/**
 * 小贾影视仓 v23.3: 播放页「线路 / 选集」横排胶囊适配器。
 * 与竖屏卡片式播放信息区配套(见 activity_play.xml 的 play_line_list / play_episode_list)。
 */
public class PlayChipAdapter extends BaseQuickAdapter<String, BaseViewHolder> {

    private int selectedIndex = -1;

    public PlayChipAdapter() {
        super(R.layout.item_play_chip);
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
        // 选中项高亮: 胶囊描边加粗 + 文字全白
        // 注: 本工程用的是 BaseRecyclerViewAdapterHelper 2.9.49, 该版本没有
        // getBindingAdapterPosition()（3.x 才引入), 用 getAdapterPosition()。
        int pos = helper.getAdapterPosition();
        boolean sel = pos >= 0 && pos == selectedIndex;
        helper.setTextColor(R.id.tv_chip, sel ? 0xFFFFFFFF : 0xB3FFFFFF);
    }
}