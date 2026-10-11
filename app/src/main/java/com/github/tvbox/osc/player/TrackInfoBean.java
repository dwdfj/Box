package com.github.tvbox.osc.player;

public class TrackInfoBean {
    public int trackId;
    //渲染器ID（exo）
    public int renderId;
    //分组ID（exo）
    public int trackGroupId;

    public String name;
    public String language;
    public boolean selected;

    // 小贾影视仓 v23.3: 画质切换用(仅 video 轨会填)。
    // width/height 用于给「4K / 1080P / 720P」这类档位归类排序, bitrate 用于兜底文案。
    public int width;
    public int height;
    public int bitrate;
}
