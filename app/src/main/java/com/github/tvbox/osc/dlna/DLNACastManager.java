package com.github.tvbox.osc.dlna;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 小贾影视仓 v23.6: 投屏总入口(单例)。对应 AVBox 的 {@code dlna/DLNACastManager}。
 *
 * <p>生命周期与 AVBox 一致: 进入投屏页 {@link #init(Context)} (拿 multicast lock + 开搜),
 * 离开时 {@link #release()} (放锁 + 清列表)。
 *
 * <p>它对外只暴露三件事: 设备快照 {@link #getDevices()}、设备变化回调 {@link DeviceListener}、
 * 以及 {@link #cast(CastDevice, CastVideo, CastCallback)}。所有网络动作都在子线程, 回调回主线程。
 */
public class DLNACastManager {

    private static final String TAG = "DLNACastManager";

    /** 一次 SSDP 搜索的时长。 */
    private static final long SEARCH_TIMEOUT_MS = 4500L;

    public interface DeviceListener {
        /** 设备列表有增减(主线程回调)。 */
        void onDeviceChanged();
    }

    public interface CastCallback {
        /** 投屏结果(主线程回调)。 */
        void onResult(boolean success, String msg);
    }

    private static final DLNACastManager INSTANCE = new DLNACastManager();

    public static DLNACastManager get() {
        return INSTANCE;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, CastDevice> devices = new LinkedHashMap<String, CastDevice>();
    private final AtomicBoolean searching = new AtomicBoolean(false);

    private WifiManager.MulticastLock multicastLock;
    private volatile DeviceListener deviceListener;

    private DLNACastManager() {
    }

    public void setDeviceListener(DeviceListener listener) {
        this.deviceListener = listener;
    }

    /** 当前已发现设备(按名称排序)的快照。 */
    public List<CastDevice> getDevices() {
        List<CastDevice> list;
        synchronized (devices) {
            list = new ArrayList<CastDevice>(devices.values());
        }
        Collections.sort(list, new Comparator<CastDevice>() {
            @Override
            public int compare(CastDevice a, CastDevice b) {
                String x = a.name == null ? "" : a.name;
                String y = b.name == null ? "" : b.name;
                return x.compareToIgnoreCase(y);
            }
        });
        return list;
    }

    /** 进入投屏: 拿组播锁并开始搜索。重复调用只会触发一次搜索。 */
    public void init(Context context) {
        acquireMulticastLock(context);
        search();
    }

    /** 离开投屏: 释放组播锁、清空设备、允许下次重新搜。 */
    public void release() {
        releaseMulticastLock();
        synchronized (devices) {
            devices.clear();
        }
        searching.set(false);
    }

    /** 开始一轮 SSDP 搜索(已在搜则直接返回)。 */
    public void search() {
        if (!searching.compareAndSet(false, true)) return;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    DlnaDiscovery.search(SEARCH_TIMEOUT_MS, new DlnaDiscovery.DeviceFound() {
                        @Override
                        public void onFound(CastDevice device) {
                            boolean changed = false;
                            synchronized (devices) {
                                if (device.id != null && !devices.containsKey(device.id)) {
                                    devices.put(device.id, device);
                                    changed = true;
                                }
                            }
                            if (changed) notifyDeviceChanged();
                        }
                    });
                } catch (Throwable t) {
                    Log.w(TAG, "search failed: " + t.getMessage());
                } finally {
                    searching.set(false);
                }
            }
        }, "dlna-ssdp");
        t.setDaemon(true);
        t.start();
    }

    /** 把地址推给指定设备(子线程执行, 结果回主线程)。 */
    public void cast(final CastDevice device, final CastVideo video, final CastCallback callback) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                String msg = "";
                try {
                    if (device == null || device.controlUrl == null) {
                        throw new Exception("设备已离线");
                    }
                    DlnaAvTransport.cast(device, video);
                    ok = true;
                } catch (Throwable t) {
                    msg = (t.getMessage() == null) ? t.toString() : t.getMessage();
                    Log.w(TAG, "cast failed: " + msg);
                }
                final boolean resultOk = ok;
                final String resultMsg = msg;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (callback != null) callback.onResult(resultOk, resultMsg);
                    }
                });
            }
        }, "dlna-cast");
        t.setDaemon(true);
        t.start();
    }

    private void notifyDeviceChanged() {
        main.post(new Runnable() {
            @Override
            public void run() {
                DeviceListener listener = deviceListener;
                if (listener != null) listener.onDeviceChanged();
            }
        });
    }

    @SuppressLint("Wakelock")
    private void acquireMulticastLock(Context context) {
        try {
            if (multicastLock != null && multicastLock.isHeld()) return;
            if (context == null) return;
            WifiManager wifi = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) return;
            WifiManager.MulticastLock lock = wifi.createMulticastLock("tvbox_dlna_cast");
            lock.setReferenceCounted(false);
            lock.acquire();
            multicastLock = lock;
        } catch (Throwable t) {
            Log.d(TAG, "acquire multicast lock failed: " + t.getMessage());
        }
    }

    private void releaseMulticastLock() {
        try {
            if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        } catch (Throwable t) {
            Log.d(TAG, "release multicast lock failed: " + t.getMessage());
        }
        multicastLock = null;
    }
}
