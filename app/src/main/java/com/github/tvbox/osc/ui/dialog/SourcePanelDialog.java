package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 小贾影视仓 v15.4: 统一「信号源」面板(两段式抽屉)。
 * 左列 = 线路(配置级: 预置+历史+当前), 右列 = 站点(SourceBean, 当前线路内, 宫格)。
 * 交互全部回抛给 HomeActivity 决策:
 *   站点切换 -> onSwitchSite (HomeActivity 走原地切站 switchHomeSourceInPlace, 不重启);
 *   线路切换 -> onSwitchLine (配置级, HomeActivity 持久化后 reloadHome);
 *   首页样式/线路配置 -> onToggleHomeStyle / onOpenConfig。
 */
public class SourcePanelDialog extends BaseDialog {

    /** 线路条目(显示名 + 配置地址) */
    public static class LineItem {
        public String name;
        public String url;

        public LineItem(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }

    public interface OnSourcePanelAction {
        void onSwitchLine(LineItem item);

        void onSwitchSite(SourceBean site);

        void onOpenConfig();

        void onToggleHomeStyle();
    }

    private OnSourcePanelAction action;

    public SourcePanelDialog(@NonNull @NotNull Context context,
                             List<LineItem> lines, int lineSelect,
                             List<SourceBean> sites, int siteSelect,
                             String styleText) {
        super(context);
        setContentView(R.layout.dialog_source_panel);

        // ---------- 左列: 线路 ----------
        TvRecyclerView lineList = findViewById(R.id.panelLineList);
        SelectDialogAdapter<LineItem> lineAdapter = new SelectDialogAdapter<>(
                new SelectDialogAdapter.SelectDialogInterface<LineItem>() {
                    @Override
                    public void click(LineItem value, int pos) {
                        if (action != null) {
                            dismiss();
                            action.onSwitchLine(value);
                        }
                    }

                    @Override
                    public String getDisplay(LineItem val) {
                        return val.name;
                    }
                }, new DiffUtil.ItemCallback<LineItem>() {
                    @Override
                    public boolean areItemsTheSame(@NonNull LineItem oldItem, @NonNull LineItem newItem) {
                        return oldItem.url.equals(newItem.url);
                    }

                    @Override
                    public boolean areContentsTheSame(@NonNull LineItem oldItem, @NonNull LineItem newItem) {
                        return oldItem.name.equals(newItem.name) && oldItem.url.equals(newItem.url);
                    }
                });
        if (lineSelect < 0 || lineSelect >= lines.size()) lineSelect = 0;
        lineAdapter.setData(lines, lineSelect);
        lineList.setAdapter(lineAdapter);
        lineList.setSelectedPosition(lineSelect);
        lineList.setSelection(lineSelect);
        final int flineSelect = lineSelect;
        lineList.post(new Runnable() {
            @Override
            public void run() {
                if (flineSelect > 4) {
                    lineList.smoothScrollToPosition(flineSelect);
                    lineList.setSelectionWithSmooth(flineSelect);
                }
            }
        });

        // ---------- 右列: 站点(自适应宫格, 同旧站点选择器 1~3 列) ----------
        TvRecyclerView siteList = findViewById(R.id.panelSiteList);
        int spanCount = (int) Math.floor(sites.size() / 10.0);
        if (spanCount <= 1) spanCount = 1;
        if (spanCount >= 3) spanCount = 3;
        siteList.setLayoutManager(new V7GridLayoutManager(context, spanCount));
        SelectDialogAdapter<SourceBean> siteAdapter = new SelectDialogAdapter<>(
                new SelectDialogAdapter.SelectDialogInterface<SourceBean>() {
                    @Override
                    public void click(SourceBean value, int pos) {
                        if (action != null) {
                            dismiss();
                            action.onSwitchSite(value);
                        }
                    }

                    @Override
                    public String getDisplay(SourceBean val) {
                        return val.getName();
                    }
                }, new DiffUtil.ItemCallback<SourceBean>() {
                    @Override
                    public boolean areItemsTheSame(@NonNull SourceBean oldItem, @NonNull SourceBean newItem) {
                        return oldItem.getKey().equals(newItem.getKey());
                    }

                    @Override
                    public boolean areContentsTheSame(@NonNull SourceBean oldItem, @NonNull SourceBean newItem) {
                        return oldItem.getKey().equals(newItem.getKey()) && oldItem.getName().equals(newItem.getName());
                    }
                });
        if (siteSelect < 0 || siteSelect >= sites.size()) siteSelect = 0;
        siteAdapter.setData(sites, siteSelect);
        siteList.setAdapter(siteAdapter);
        siteList.setSelectedPosition(siteSelect);
        siteList.setSelection(siteSelect);
        final int fsiteSelect = siteSelect;
        siteList.post(new Runnable() {
            @Override
            public void run() {
                if (fsiteSelect > 9) {
                    siteList.smoothScrollToPosition(fsiteSelect);
                    siteList.setSelectionWithSmooth(fsiteSelect);
                }
            }
        });

        // ---------- 底部操作: 样式 / 配置 / 返回 ----------
        if (styleText != null) {
            android.widget.TextView tvStyle = findViewById(R.id.panelStyleBtn);
            tvStyle.setText("首页样式: " + styleText);
        }
        findViewById(R.id.panelStyleBtn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (action != null) {
                    dismiss();
                    action.onToggleHomeStyle();
                }
            }
        });
        findViewById(R.id.panelConfigBtn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (action != null) {
                    dismiss();
                    action.onOpenConfig();
                }
            }
        });
        findViewById(R.id.panelCloseBtn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
    }

    public void setOnSourcePanelAction(OnSourcePanelAction action) {
        this.action = action;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 初始焦点给右列站点(高频操作)
        TvRecyclerView siteList = findViewById(R.id.panelSiteList);
        if (siteList != null) siteList.requestFocus();
    }
}
