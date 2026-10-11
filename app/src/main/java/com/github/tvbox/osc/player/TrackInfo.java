package com.github.tvbox.osc.player;

import java.util.ArrayList;
import java.util.List;

public class TrackInfo {
    private final List<TrackInfoBean> audio;
    private final List<TrackInfoBean> subtitle;
    // 小贾影视仓 v23.3: 视频轨(多清晰度 / 4K 画质切换用)。
    // ExoPlayer 的 DASH/HLS 流会带多个 video track(不同分辨率/码率),
    // 之前只收了 audio 与 text, 导致 UI 上无法列出可选画质。
    private final List<TrackInfoBean> video;

    public TrackInfo() {
        audio = new ArrayList<>();
        subtitle = new ArrayList<>();
        video = new ArrayList<>();
    }

    public List<TrackInfoBean> getAudio() {
        return audio;
    }

    public int getAudioSelected(boolean track) {
        return getSelected(audio, track);
    }

    public int getSubtitleSelected(boolean track) {
        return getSelected(subtitle, track);
    }

    public int getSelected(List<TrackInfoBean> list, boolean track) {
        int i = 0;
        for (TrackInfoBean audio : list) {
            if (audio.selected) return track ? audio.trackId : i;
            i++;
        }
        return 99999;
    }

    public void addAudio(TrackInfoBean audio) {
        this.audio.add(audio);
    }

    public List<TrackInfoBean> getSubtitle() {
        return subtitle;
    }

    public void addSubtitle(TrackInfoBean subtitle) {
        this.subtitle.add(subtitle);
    }

    /** 小贾影视仓 v23.3: 可选画质(video 轨) */
    public List<TrackInfoBean> getVideo() {
        return video;
    }

    public void addVideo(TrackInfoBean v) {
        this.video.add(v);
    }

    /**
     * 小贾影视仓 v23.3: 是否存在多清晰度可选。
     * 只有 1 条 video 轨时谈不上"选择画质", UI 应隐藏画质按钮(避免给用户一个只能点出单项的死按钮)。
     */
    public boolean hasMultipleVideo() {
        return video.size() > 1;
    }
}