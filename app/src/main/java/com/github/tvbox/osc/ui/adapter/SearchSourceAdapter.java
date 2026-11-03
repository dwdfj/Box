package com.github.tvbox.osc.ui.adapter;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;

import java.util.List;

/**
 * 小贾影视仓: 搜索结果来源渠道列表
 * v27.18: 增加「当前选中来源」高亮 —— 用户反馈"点了哪个线路看不出来, 需要选择框"。
 */
public class SearchSourceAdapter extends BaseQuickAdapter<String, BaseViewHolder> {

    /** 当前选中的来源下标(0 = 全部结果) */
    private int selectedPos = 0;

    public SearchSourceAdapter() {
        super(R.layout.item_search_source, null);
    }

    public SearchSourceAdapter(List<String> data) {
        super(R.layout.item_search_source, data);
    }

    public static DiffUtil.ItemCallback<String> diff = new DiffUtil.ItemCallback<String>() {
        @Override
        public boolean areItemsTheSame(@NonNull String oldItem, @NonNull String newItem) {
            return oldItem.equals(newItem);
        }

        @Override
        public boolean areContentsTheSame(@NonNull String oldItem, @NonNull String newItem) {
            return oldItem.equals(newItem);
        }
    };

    public void setSelectedPos(int pos) {
        int old = selectedPos;
        selectedPos = pos;
        if (old >= 0 && old < getItemCount()) notifyItemChanged(old);
        if (pos >= 0 && pos < getItemCount()) notifyItemChanged(pos);
    }

    @Override
    protected void convert(@NonNull BaseViewHolder helper, String item) {
        helper.setText(R.id.tvSourceName, item);
        int pos = helper.getAdapterPosition();
        if (pos < 0) pos = 0;
        // state_selected 驱动 shape_chip_select_pill 的紫底白边
        helper.itemView.setSelected(pos == selectedPos);
        if (helper.itemView instanceof android.widget.TextView) {
            ((android.widget.TextView) helper.itemView)
                    .getPaint().setFakeBoldText(pos == selectedPos);
        }
    }
}
