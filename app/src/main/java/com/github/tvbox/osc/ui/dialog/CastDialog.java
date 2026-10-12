package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.dlna.CastDevice;
import com.github.tvbox.osc.dlna.CastVideo;
import com.github.tvbox.osc.dlna.DLNACastManager;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.owen.tvrecyclerview.widget.TvRecyclerView;

import java.util.List;

/**
 * 小贾影视仓 v23.6: 投屏设备选择框。
 *
 * <p>v23.3 的"投屏"只是起一个 {@code android.settings.WIFI_DISPLAY_SETTINGS} 系统意图,
 * 手机上这个页面大多不存在 → 直接弹"本机不支持无线投屏"。现在改成真·DLNA:
 * 列出同一 Wi-Fi 下可接收的电视/盒子, 点一下就把地址推过去。
 */
public class CastDialog extends BaseDialog {

    private final String videoUrl;
    private final String videoTitle;
    private final long position;

    private TextView statusView;
    private SelectDialogAdapter<CastDevice> adapter;

    private final DLNACastManager.DeviceListener deviceListener = new DLNACastManager.DeviceListener() {
        @Override
        public void onDeviceChanged() {
            refresh();
        }
    };

    public CastDialog(@NonNull Context context, String videoUrl, String videoTitle, long position) {
        super(context);
        this.videoUrl = videoUrl;
        this.videoTitle = videoTitle;
        this.position = position;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_cast);

        statusView = findViewById(R.id.cast_status);

        View refresh = findViewById(R.id.cast_refresh);
        if (refresh != null) {
            refresh.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    DLNACastManager.get().release();
                    DLNACastManager.get().init(getContext());
                    refresh();
                }
            });
        }

        DLNACastManager.get().setDeviceListener(deviceListener);
        DLNACastManager.get().init(getContext());
        refresh();
    }

    /** 用当前已发现设备重刷列表与状态文案(主线程)。 */
    private void refresh() {
        if (statusView == null) return;
        List<CastDevice> devices = DLNACastManager.get().getDevices();

        TvRecyclerView list = findViewById(R.id.cast_list);
        if (list != null) {
            if (adapter == null) {
                adapter = new SelectDialogAdapter<CastDevice>(
                        new SelectDialogAdapter.SelectDialogInterface<CastDevice>() {
                            @Override
                            public void click(CastDevice value, int pos) {
                                castTo(value);
                            }

                            @Override
                            public String getDisplay(CastDevice val) {
                                return val == null ? "" : val.getDisplayName();
                            }
                        },
                        CAST_DEVICE_DIFF,
                        true);
                list.setAdapter(adapter);
            }
            adapter.setData(devices, -1);
        }

        statusView.setText(devices.isEmpty()
                ? getContext().getString(R.string.cast_searching)
                : getContext().getString(R.string.cast_found, devices.size()));
    }

    private void castTo(CastDevice device) {
        if (videoUrl == null || videoUrl.length() == 0) {
            Toast.makeText(getContext(), R.string.cast_no_url, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(getContext(),
                getContext().getString(R.string.cast_connecting, device.name == null ? "" : device.name),
                Toast.LENGTH_SHORT).show();

        DLNACastManager.get().cast(device, new CastVideo(videoUrl, videoTitle, position),
                new DLNACastManager.CastCallback() {
                    @Override
                    public void onResult(boolean success, String msg) {
                        Context ctx = getContext();
                        if (ctx == null) return;
                        if (success) {
                            Toast.makeText(ctx, R.string.cast_ok, Toast.LENGTH_LONG).show();
                            dismiss();
                        } else {
                            Toast.makeText(ctx, ctx.getString(R.string.cast_fail, msg), Toast.LENGTH_LONG).show();
                        }
                    }
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 离开投屏: 摘掉回调并释放组播锁, 不在后台空转
        DLNACastManager.get().setDeviceListener(null);
        DLNACastManager.get().release();
    }

    private static final DiffUtil.ItemCallback<CastDevice> CAST_DEVICE_DIFF =
            new DiffUtil.ItemCallback<CastDevice>() {
                @Override
                public boolean areItemsTheSame(@NonNull CastDevice oldItem, @NonNull CastDevice newItem) {
                    return oldItem.equals(newItem);
                }

                @Override
                public boolean areContentsTheSame(@NonNull CastDevice oldItem, @NonNull CastDevice newItem) {
                    return oldItem.equals(newItem);
                }
            };
}
