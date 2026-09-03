package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cache.RoomDataManger;
import com.github.tvbox.osc.cache.VodCollect;
import com.github.tvbox.osc.ui.activity.CollectActivity;
import com.github.tvbox.osc.ui.activity.HistoryActivity;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class ConfirmClearDialog extends BaseDialog {
    private final TextView tvYes;
    private final TextView tvNo;

    public ConfirmClearDialog(@NonNull @NotNull Context context, String type) {
        super(context);
        setContentView(R.layout.dialog_confirm);
        setCanceledOnTouchOutside(true);
        tvYes = findViewById(R.id.btnConfirm);
        tvNo = findViewById(R.id.btnCancel);

        tvYes.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 小贾影视仓 v15.6.1: 原用 type == "Collect" 比较字符串 —— 依赖字符串常量池侥幸生效,
                // 一旦调用方改为动态拼接传参就会静默失效(清空操作不执行且无报错)。统一改 equals + 空 adapter 防御。
                if ("Collect".equals(type)) {
                    List<VodCollect> vodInfoList = new ArrayList<>();
                    if (CollectActivity.collectAdapter != null) {
                        CollectActivity.collectAdapter.setNewData(vodInfoList);
                        CollectActivity.collectAdapter.notifyDataSetChanged();
                    }
                    RoomDataManger.deleteVodCollectAll();
                    // if removing all History
                } else if ("History".equals(type)) {
                    List<VodInfo> vodInfoList = new ArrayList<>();
                    if (HistoryActivity.historyAdapter != null) {
                        HistoryActivity.historyAdapter.setNewData(vodInfoList);
                        HistoryActivity.historyAdapter.notifyDataSetChanged();
                    }
                    RoomDataManger.deleteVodRecordAll();
                }

                ConfirmClearDialog.this.dismiss();
            }
        });
        tvNo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ConfirmClearDialog.this.dismiss();
            }
        });
    }

}