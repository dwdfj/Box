package com.github.tvbox.osc.widget;

import android.content.Context;
import android.util.AttributeSet;

import com.owen.tvrecyclerview.widget.TvRecyclerView;

/**
 * 小贾影视仓 v24.7.1: TvRecyclerView 防 NPE 守卫。
 *
 * 背景(用户 19:00 崩溃): HomeActivity 顶部分类 ViewPager 的页面是 BaseLazyFragment
 * (GridFragment/UserFragment), init() 延迟到"首次可见"才执行; 而 ViewPager 预加载邻页时
 * 会先 inflate 视图并触发布局 —— 此时 fragment_grid/fragment_user 里的 TvRecyclerView
 * 还没走到 initView() 的 setLayoutManager, 库内 onLayout →
 * getLastVisibleAndAndFocusablePosition → mLayoutManager.findViewByPosition 直接 NPE。
 *
 * 修法: 布局阶段吞掉这一特定 NPE —— layoutManager 尚未就绪时跳过本轮布局,
 * init() 里 setLayoutManager 会触发 requestLayout, 下一轮布局自然恢复, 无副作用。
 * 仅吞 NullPointerException, 不掩盖其他真实异常。
 */
public class SafeTvRecyclerView extends TvRecyclerView {

    public SafeTvRecyclerView(Context context) {
        super(context);
    }

    public SafeTvRecyclerView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SafeTvRecyclerView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    public void onLayout(boolean changed, int l, int t, int r, int b) {
        try {
            super.onLayout(changed, l, t, r, b);
        } catch (NullPointerException ignored) {
            // layoutManager 未就绪(TvRecyclerView.getLastVisibleAndFocusablePosition), 跳过本轮布局
        }
    }
}
