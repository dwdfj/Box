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

        /** 小贾影视仓 v21: 加载本地 .py 单源(弹出系统文件选择器) */
        void onPickLocalPy();

        /** 小贾影视仓 v27.22: 长按本地 py 站点 —— 删除该 py(文件和记录一起清) */
        void onDeletePy(SourceBean site);
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
        // 小贾影视仓 v27.22: 列数收紧。旧版按站点数 10 个一列最多铺 3 列, 右列宽度只有 ~700 设计单位,
        // 3 列时每格 ≈230 —— 「[短剧] qimao(本地py)」这类长名字被截成「[短剧] qi…」, 用户看不到全名。
        // 现在 18 站以内单列(整行都是名字), 更多才两列; 长名字最多两行显示。
        int spanCount = sites.size() <= 18 ? 1 : 2;
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
        // 小贾影视仓 v27.22: 长按本地 py 站点 = 删除(先弹确认框, 由 HomeActivity 执行)。
        // 之前 py 只能加载不能删, 加载错了就一直挂在站点列表最前面。
        boolean hasLocalPy = false;
        for (SourceBean sb : sites) {
            if (sb != null && sb.getKey() != null && sb.getKey().startsWith("py_local_")) {
                hasLocalPy = true;
                break;
            }
        }
        final boolean canDeletePy = hasLocalPy;
        siteAdapter.setOnItemLongClick(new SelectDialogAdapter.OnItemLongClick<SourceBean>() {
            @Override
            public boolean onLongClick(SourceBean value, int pos) {
                if (value == null || value.getKey() == null
                        || !value.getKey().startsWith("py_local_")) {
                    return false;   // 线路自带的站点不支持删
                }
                if (action != null) {
                    dismiss();
                    action.onDeletePy(value);
                }
                return true;
            }
        });
        if (canDeletePy) {
            android.widget.TextView siteTitle = findViewById(R.id.panelSiteTitle);
            if (siteTitle != null) siteTitle.setText("站点（长按 py 可删除）");
        }
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
            // 小贾影视仓 v23.3: 底部四按钮改为等宽均分(weight=1), 每个只有约 5 个汉字宽。
            // 原文案「首页样式: 宫格」(7 字) 会被 ellipsize 截成「首页样…」。
            // 现只保留前缀, 当前样式值放到 contentDescription 里(无障碍/长按提示仍可读到)。
            tvStyle.setText("首页样式");
            tvStyle.setContentDescription("首页样式: " + styleText);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                tvStyle.setTooltipText("首页样式: " + styleText);
            }
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
        // 小贾影视仓 v21: 加载本地 .py 单源
        findViewById(R.id.panelPyBtn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (action != null) {
                    dismiss();
                    action.onPickLocalPy();
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
    public void show() {
        super.show();
        // 小贾影视仓 v27.18: 面板必须拿到"确定高度", 否则内部 0dp/weight 的列表没有可分配空间。
        // Theme.Dialog 默认高度是 WRAP_CONTENT, 之前 ConstraintLayout 的 0dp 双向约束链
        // 在 wrap 窗口里算出来是 0 —— 表现就是"线路一列空的, 什么也选不了"。
        try {
            android.view.Window w = getWindow();
            if (w != null) {
                w.setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT,
                        android.view.WindowManager.LayoutParams.MATCH_PARENT);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 初始焦点给右列站点(高频操作)
        TvRecyclerView siteList = findViewById(R.id.panelSiteList);
        if (siteList != null) siteList.requestFocus();
    }
}
