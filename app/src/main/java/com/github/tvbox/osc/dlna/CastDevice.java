package com.github.tvbox.osc.dlna;

import android.text.TextUtils;

/**
 * 小贾影视仓 v23.6: 一台可投屏的 DLNA 设备(MediaRenderer)。
 * 对应 AVBox 的 {@code dlna/CastDevice}(只保留 DLNA 一路, 裁剪掉 TVBox 互投)。
 */
public class CastDevice {

    public static final int TYPE_DLNA = 2;

    /** UDN(设备唯一名), 用于去重; 拿不到 UDN 时退化为 LOCATION。 */
    public final String id;
    /** friendlyName, 展示用。 */
    public final String name;
    /** 设备描述 XML 地址(SSDP 响应里的 LOCATION)。 */
    public final String location;
    /** 设备描述 XML 里解析出的 AVTransport controlURL(绝对地址)。 */
    public String controlUrl;

    public CastDevice(String id, String name, String location) {
        this.id = id;
        this.name = name;
        this.location = location;
    }

    public String getDisplayName() {
        return "DLNA  " + (name == null ? "" : name);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CastDevice)) return false;
        CastDevice that = (CastDevice) other;
        return TextUtils.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
