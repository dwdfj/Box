package com.github.tvbox.osc.ui.tv.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.AttrRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chad.library.adapter.base.BaseMultiItemQuickAdapter;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.chad.library.adapter.base.entity.MultiItemEntity;
import android.content.res.Resources;

import com.github.tvbox.osc.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


/**
 * @author pj567
 * @date :2020/12/23
 * @description:
 */
public class SearchKeyboard extends FrameLayout {
    private RecyclerView mRecyclerView;
    private List<String> keys;
    private List<Keyboard> keyboardList = new ArrayList<>();
    private OnSearchKeyListener searchKeyListener;
    private OnFocusChangeListener focusChangeListener = new OnFocusChangeListener() {
        @Override
        public void onFocusChange(View itemView, boolean hasFocus) {
            if (null != itemView && itemView != mRecyclerView) {
                itemView.setSelected(hasFocus);
            }
        }
    };

    public SearchKeyboard(@NonNull Context context) {
        this(context, null);
    }

    public SearchKeyboard(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SearchKeyboard(@NonNull Context context, @Nullable AttributeSet attrs, @AttrRes int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initView();
    }

    private void initView() {
        // 小贾影视仓: 原来在字段初始化器里用 HomeActivity.getRes() 取字符串, 当 HomeActivity 尚未 init
        // (如 SearchReceiver 直接拉起 SearchActivity) 时 res 为 null, 会 NPE 崩。改用本 View 自己的
        // Context 资源, 任何 inflate 阶段都可用, 不再依赖其它 Activity 的静态资源。
        Resources resources = getContext().getResources();
        // 小贾影视仓 v27.18: 键盘只留 A-Z / 0-9 —— "远程搜索""删除"移到搜索页底部按钮行。
        // 原来这两个键占满第一行 6 格里的 2 格(各 span 3), 又大又靠上, 手机上极容易误触。
        keys = Arrays.asList("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z", "1", "2", "3", "4", "5", "6", "7", "8", "9", "0");
        View view = LayoutInflater.from(getContext()).inflate(R.layout.layout_keyborad, this);
        mRecyclerView = view.findViewById(R.id.mRecyclerView);
        GridLayoutManager manager = new GridLayoutManager(getContext(), 6);
        mRecyclerView.setLayoutManager(manager);
        mRecyclerView.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override
            public void onChildViewAttachedToWindow(@NonNull View child) {
                if (child.isFocusable() && null == child.getOnFocusChangeListener()) {
                    child.setOnFocusChangeListener(focusChangeListener);
                }
            }

            @Override
            public void onChildViewDetachedFromWindow(@NonNull View view) {

            }
        });
        int size = keys.size();
        for (int i = 0; i < size; i++) {
            keyboardList.add(new Keyboard(1, keys.get(i)));
        }
        final KeyboardAdapter adapter = new KeyboardAdapter(keyboardList);
        mRecyclerView.setAdapter(adapter);
        adapter.setSpanSizeLookup(new BaseQuickAdapter.SpanSizeLookup() {
            @Override
            public int getSpanSize(GridLayoutManager gridLayoutManager, int position) {
                // v27.18: 全是普通字符键, 每格 1 span(6 列)
                return 1;
            }
        });

        adapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                Keyboard keyboard = (Keyboard) adapter.getItem(position);
                if (searchKeyListener != null) {
                    searchKeyListener.onSearchKey(position, keyboard.getKey());
                }
            }
        });
    }

    static class Keyboard implements MultiItemEntity {
        private int itemType;
        private String key;

        private Keyboard(int itemType, String key) {
            this.itemType = itemType;
            this.key = key;
        }

        @Override
        public int getItemType() {
            return itemType;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }
    }

    private static class KeyboardAdapter extends BaseMultiItemQuickAdapter<Keyboard, BaseViewHolder> {

        private KeyboardAdapter(List<Keyboard> data) {
            super(data);
            addItemType(1, R.layout.item_keyboard);
        }

        @Override
        protected void convert(BaseViewHolder helper, Keyboard item) {
            switch (helper.getItemViewType()) {
                case 1:
                    helper.setText(R.id.keyName, item.key);
                    break;
            }
        }
    }

    public void setOnSearchKeyListener(OnSearchKeyListener listener) {
        searchKeyListener = listener;
    }

    public interface OnSearchKeyListener {
        void onSearchKey(int pos, String key);
    }
}