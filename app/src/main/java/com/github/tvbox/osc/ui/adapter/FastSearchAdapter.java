package com.github.tvbox.osc.ui.adapter;

import android.text.TextUtils;
import android.widget.ImageView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.ImgUtil;

import java.util.ArrayList;

import me.jessyan.autosize.utils.AutoSizeUtils;

public class FastSearchAdapter extends BaseQuickAdapter<Movie.Video, BaseViewHolder> {
    public FastSearchAdapter() {
        super(R.layout.item_search, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder helper, Movie.Video item) {

        // with preview
        helper.setText(R.id.tvName, item.name);
        helper.setText(R.id.tvSite, ApiConfig.get().getSource(item.sourceKey).getName());
        helper.setVisible(R.id.tvNote, item.note != null && !item.note.isEmpty());
        if (item.note != null && !item.note.isEmpty()) {
            helper.setText(R.id.tvNote, item.note);
        }
        ImageView ivThumb = helper.getView(R.id.ivThumb);
        // v15.13: 封面闪烁修复 —— 对齐 SearchAdapter(v15.4.2): 多站并发搜索时结果不断到达,
        // adapter 反复 setNewData/addData 触发整墙重绘, 若每次都对同一 URL 重新走
        // placeholder→加载 流程会整墙闪烁跳动。用 tag 记录当前 URL: 相同则跳过(不重载不闪)。
        String pic = item.pic == null ? "" : item.pic;
        Object tag = ivThumb.getTag();
        if (pic.isEmpty()) {
            if (tag != null) {
                ivThumb.setTag(null);
                ivThumb.setImageResource(R.drawable.img_loading_placeholder);
            }
            return;
        }
        if (pic.equals(tag)) {
            return; // 同一封面已加载/加载中, 跳过避免闪烁
        }
        ivThumb.setTag(pic);
        ImgUtil.load(pic, ivThumb, 14);
    }
}
