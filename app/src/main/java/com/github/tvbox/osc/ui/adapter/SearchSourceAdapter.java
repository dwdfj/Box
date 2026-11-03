package com.github.tvbox.osc.ui.adapter;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;

import java.util.List;

/**
 * 小贾影视仓: 搜索结果来源渠道列表
 */
public class SearchSourceAdapter extends BaseQuickAdapter<String, BaseViewHolder> {

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

    @Override
    protected void convert(@NonNull BaseViewHolder helper, String item) {
        helper.setText(R.id.tvSourceName, item);
    }
}
