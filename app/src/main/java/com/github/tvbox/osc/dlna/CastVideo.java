package com.github.tvbox.osc.dlna;

/**
 * 小贾影视仓 v23.6: 一次投屏要推给电视的内容(地址 + 标题 + 起播位置)。
 * 对应 AVBox 的 {@code CastVideo}(裁剪掉 headers 字段: 标准 DLNA 渲染器读不到附加请求头)。
 */
public class CastVideo {

    public final String url;
    public final String name;
    /** 毫秒, > 0 时投屏成功后补一个 REL_TIME seek 续播。 */
    public final long position;

    public CastVideo(String url, String name, long position) {
        this.url = url;
        this.name = name;
        this.position = position;
    }
}
