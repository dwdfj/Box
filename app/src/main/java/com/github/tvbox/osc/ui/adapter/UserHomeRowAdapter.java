package com.github.tvbox.osc.ui.adapter;

import android.text.TextUtils;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.ImgUtil;
import com.orhanobut.hawk.Hawk;

import java.util.ArrayList;

/**
 * @author 小贾影视仓 v15.7
 * @description 首页「卡带」迷你卡适配器(热门推荐 / 继续观看 共用):
 * 1. 海报 + 右上角 note 角标(评分/进度/备注) + 底部片名;
 * 2. deleteAware=true 时才参与「删除模式」(热门带在播放历史模式下长按进入),
 *    继续观看带恒 false, 避免全局 hotVodDelete 标志把两条带都染成删除态。
 */
public class UserHomeRowAdapter extends BaseQuickAdapter<Movie.Video, BaseViewHolder> {
    private final boolean deleteAware;

    /** v15.7 迷你卡(95x128) */
    public UserHomeRowAdapter(boolean deleteAware) {
        this(deleteAware, R.layout.item_user_home_small);
    }

    /**
     * v15.8: 允许指定卡片布局 —— 沉浸式海报墙用「大卡」item_user_home_big(海报 150x200),
     * 逻辑与迷你卡完全一致(角标/片名/删除遮罩/图片加载), 仅布局不同。
     */
    public UserHomeRowAdapter(boolean deleteAware, int layoutResId) {
        super(layoutResId, new ArrayList<>());
        this.deleteAware = deleteAware;
    }

    @Override
    protected void convert(BaseViewHolder helper, Movie.Video item) {
        FrameLayout delFrame = helper.getView(R.id.delFrameLayout);
        if (deleteAware && HawkConfig.hotVodDelete) {
            delFrame.setVisibility(View.VISIBLE);
        } else {
            delFrame.setVisibility(View.GONE);
        }

        TextView tvNote = helper.getView(R.id.tvNote);
        if (item.note == null || item.note.isEmpty()) {
            tvNote.setVisibility(View.GONE);
        } else {
            tvNote.setText(item.note);
            tvNote.setVisibility(View.VISIBLE);
        }
        helper.setText(R.id.tvName, item.name);

        ImageView ivThumb = helper.getView(R.id.ivThumb);
        if (!TextUtils.isEmpty(item.pic)) {
            ImgUtil.load(item.pic, ivThumb, 14);
        } else {
            ivThumb.setImageResource(R.drawable.img_loading_placeholder);
        }
    }
}
