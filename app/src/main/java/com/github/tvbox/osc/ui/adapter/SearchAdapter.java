package com.github.tvbox.osc.ui.adapter;

import android.text.TextUtils;
import android.widget.ImageView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.ImgUtil;
import com.orhanobut.hawk.Hawk;

import java.util.ArrayList;
import me.jessyan.autosize.utils.AutoSizeUtils;
public class SearchAdapter extends BaseQuickAdapter<Movie.Video, BaseViewHolder> {
    public SearchAdapter() {
        super(Hawk.get(HawkConfig.SEARCH_VIEW, 0) == 0 ? R.layout.item_search_lite : R.layout.item_search, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder helper, Movie.Video item) {
        // lite
        if (Hawk.get(HawkConfig.SEARCH_VIEW, 0) == 0) {
            helper.setText(R.id.tvName, String.format("%s  %s %s %s", ApiConfig.get().getSource(item.sourceKey).getName(), item.name, item.type == null ? "" : item.type, item.note == null ? "" : item.note));
        } else {// with preview
            helper.setText(R.id.tvName, item.name);
            helper.setText(R.id.tvSite, ApiConfig.get().getSource(item.sourceKey).getName());
            helper.setVisible(R.id.tvNote, item.note != null && !item.note.isEmpty());
            if (item.note != null && !item.note.isEmpty()) {
                helper.setText(R.id.tvNote, item.note);
            }
            ImageView ivThumb = helper.getView(R.id.ivThumb);
            // v15.4.2: 封面闪烁修复 —— 多站并发搜索时每站返回都 setNewData 全量重绘,
            // 若不加去重, 相同封面会反复走 placeholder→加载 流程导致整墙闪烁跳动。
            // 用默认 tag 记录当前正在加载/已加载的 pic URL: 相同则直接跳过(不重载不闪);
            // 不同(复用行换了视频)才清旧并加载新图。
            String pic = item.pic == null ? "" : item.pic;
            Object tag = ivThumb.getTag();
            if (pic.isEmpty()) {
                if (tag != null) {
                    ivThumb.setTag(null); // 原图已失效, 占位显示
                    ivThumb.setImageResource(R.drawable.img_loading_placeholder);
                }
                return;
            }
            if (pic.equals(tag)) {
                return; // 同一封面已加载/加载中, 跳过避免闪烁
            }
            ivThumb.setTag(pic);
            // takagen99 : Use Glide instead
            ImgUtil.load(pic, ivThumb, 14);
        }
    }
}
