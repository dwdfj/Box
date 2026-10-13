package com.github.tvbox.osc.bean;

import static com.github.tvbox.osc.util.RegexUtils.getPattern;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.google.gson.Gson;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * @author pj567
 * @date :2020/12/22
 * @description:
 */
public class VodInfo implements Serializable {
    public String last;//时间
    //内容id
    public String id;
    //父级id
    public int tid;
    //影片名称 <![CDATA[老爸当家]]>
    public String name;
    //类型名称
    public String type;
    //视频分类zuidam3u8,zuidall
    public String dt;
    //图片
    public String pic;
    //语言
    public String lang;
    //地区
    public String area;
    //年份
    public int year;
    public String state;
    //描述集数或者影片信息<![CDATA[共40集]]>
    public String note;
    //演员<![CDATA[张国立,蒋欣,高鑫,曹艳艳,王维维,韩丹彤,孟秀,王新]]>
    public String actor;
    //导演<![CDATA[陈国星]]>
    public String director;
    public ArrayList<VodSeriesFlag> seriesFlags;
    public LinkedHashMap<String, List<VodSeries>> seriesMap;
    public String des;// <![CDATA[权来]
    public String playFlag = null;
    public int playIndex = 0;
    public int playGroup = 0;
    public int playGroupCount = 0;
    public String playNote = "";
    public String sourceKey;
    public String playerCfg = "";
    public boolean reverseSort = false;

    public void setVideo(Movie.Video video) {
        last = video.last;
        id = video.id;
        tid = video.tid;
        name = video.name;
        type = video.type;
        // dt = video.dt;
        pic = video.pic;
        lang = video.lang;
        area = video.area;
        year = video.year;
        state = video.state;
        note = video.note;
        actor = video.actor;
        director = video.director;
        des = video.des;
        if (video.urlBean != null && video.urlBean.infoList != null && video.urlBean.infoList.size() > 0) {
            LinkedHashMap<String, List<VodSeries>> tempSeriesMap = new LinkedHashMap<>();
            seriesFlags = new ArrayList<>();
            for (Movie.Video.UrlBean.UrlInfo urlInfo : video.urlBean.infoList) {
                if (urlInfo.beanList != null && urlInfo.beanList.size() > 0) {
                    List<VodSeries> seriesList = new ArrayList<>();
                    for (Movie.Video.UrlBean.UrlInfo.InfoBean infoBean : urlInfo.beanList) {
                        seriesList.add(new VodSeries(infoBean.name, infoBean.url));
                    }
                    tempSeriesMap.put(urlInfo.flag, seriesList);
                    seriesFlags.add(new VodSeriesFlag(urlInfo.flag));
                }
            }
            seriesMap = new LinkedHashMap<>();
            // 小贾影视仓 v24.2: 线路自然排序 —— 用户反馈"各线路组合乱七八糟"。
            // 源返回的 flag 顺序常常是 4K线路②③→小猪投屏①... 交错无序;
            // 自然排序(数字按数值比、①②③按序号比)后同族线路自动相邻、编号顺向。
            // 同名 flag 保持源顺序(稳定排序), 不破坏源本身的语义。
            try {
                java.util.Collections.sort(seriesFlags, (f1, f2) -> naturalCompare(f1.name, f2.name));
            } catch (Throwable t) {
                t.printStackTrace();
            }
            for (VodSeriesFlag flag : seriesFlags) {
                List<VodSeries> list = tempSeriesMap.get(flag.name);
                assert list != null;
                // 小贾影视仓 v24.2: 选集顺向排列 —— 用户反馈"线路的级数不是顺向排列"。
                // 很多源把集数当字符串排: 第1集→第10集→第2集。若整条列表都是
                // "统一前缀+数字+统一后缀"(如 第X集/第XX集/01.xxx), 按数值升序重排;
                // 含花絮/预告等异构名的列表不动, 保持源语义。
                sortSeriesIfNeeded(list);
                if(seriesFlags.size()<=3){
                    if(isReverse(list))Collections.reverse(list);
                }
                seriesMap.put(flag.name, list);
            }
        }
    }

    /** 小贾影视仓 v24.2: 带圈数字 ①-⑳ -> 1-20, 非 0 返回 */
    private static int circledDigit(char c) {
        if (c >= '\u2460' && c <= '\u2473') return c - '\u2460' + 1;   // ①-⑳ -> 1-20
        if (c >= '\u24EB' && c <= '\u24FE') return c - '\u24EB' + 11;  // 11-20 带圈变体
        return 0;
    }

    private static boolean isNumChar(char c) {
        return Character.isDigit(c) || circledDigit(c) > 0;
    }

    private static long readNum(String s, int[] pos) {
        long v = 0;
        if (circledDigit(s.charAt(pos[0])) > 0) {
            v = circledDigit(s.charAt(pos[0]));
            pos[0]++;
        } else {
            while (pos[0] < s.length() && Character.isDigit(s.charAt(pos[0]))) {
                v = v * 10 + (s.charAt(pos[0]) - '0');
                pos[0]++;
                if (v > 99999999) v = 99999999; // 防溢出
            }
        }
        return v;
    }

    /** 小贾影视仓 v24.2: 自然比较 —— 数字段按数值比(第2集 < 第10集), 其余按字符比 */
    public static int naturalCompare(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            boolean na = isNumChar(ca), nb = isNumChar(cb);
            if (na && nb) {
                int[] pa = {i}, pb = {j};
                long va = readNum(a, pa), vb = readNum(b, pb);
                if (va != vb) return va < vb ? -1 : 1;
                i = pa[0];
                j = pb[0];
            } else {
                if (ca != cb) return ca < cb ? -1 : 1;
                i++;
                j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }

    /**
     * 小贾影视仓 v24.2: 若列表所有名字都是「统一前缀 + 数字 + 统一后缀」结构
     * (典型: 第1集/第2集/... , 01.mp4/02.mp4/...), 按数字升序重排, 解决
     * 字符串序导致的 1→10→2 乱序。异构列表(花絮/预告混排)原样保留。
     */
    private void sortSeriesIfNeeded(List<VodSeries> list) {
        try {
            int n = list.size();
            if (n < 2 || n > 999) return;
            java.util.regex.Matcher m = getPattern("^(.*?)(\\d+)([^0-9]*)$").matcher(list.get(0).name);
            if (!m.matches()) return;
            String prefix = m.group(1), suffix = m.group(3);
            long[] nums = new long[n];
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) {
                java.util.regex.Matcher mi = getPattern("^(.*?)(\\d+)([^0-9]*)$").matcher(list.get(i).name);
                if (!mi.matches() || !mi.group(1).equals(prefix) || !mi.group(3).equals(suffix)) return;
                nums[i] = Long.parseLong(mi.group(2));
                order[i] = i;
            }
            boolean asc = true, desc = true;
            for (int i = 1; i < n; i++) {
                if (nums[i] < nums[i - 1]) asc = false;
                if (nums[i] > nums[i - 1]) desc = false;
            }
            if (asc) return; // 本来就顺向, 不动
            java.util.Arrays.sort(order, (x, y) -> nums[x] != nums[y]
                    ? Long.compare(nums[x], nums[y]) : Integer.compare(x, y));
            List<VodSeries> sorted = new ArrayList<>(n);
            for (int i = 0; i < n; i++) sorted.add(list.get(order[i]));
            list.clear();
            list.addAll(sorted);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private int extractNumber(String name) {
        java.util.regex.Matcher matcher = getPattern("\\d+").matcher(name);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group());
        }
        return 0;
    }
    private boolean isReverse(List<VodInfo.VodSeries> list) {
        if (list.size() > 300) {
            return false;
        }
        int ascCount = 0, descCount = 0;
        // 比较最多前 6 个相邻元素对
        int limit = Math.min(list.size() - 1, 6);
        for (int i = 0; i < limit; i++) {
            int current = extractNumber(list.get(i).name);
            int next = extractNumber(list.get(i + 1).name);
            if (current < next) {
                ascCount++;
                if (ascCount == 2) return false;
            } else if (current > next) {
                descCount++;
                if (descCount == 2) return true;
            }
        }
        return false;
    }

    public void reverse() {
        Set<String> flags = seriesMap.keySet();
        for (String flag : flags) {
            Collections.reverse(seriesMap.get(flag));
        }
    }
    
    public int getplayIndex() {
        return this.playGroup * this.playGroupCount + this.playIndex;
    }
    
    public static class VodSeriesFlag implements Serializable {

        public String name;
        public boolean selected;

        public VodSeriesFlag() {

        }

        public VodSeriesFlag(String name) {
            this.name = name;
        }
    }

    public static class VodSeries implements Serializable {

        public String name;
        public String url;
        public boolean selected;

        public VodSeries() {
        }

        public VodSeries(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }
    
    

    @NonNull
    @Override
    public Object clone() {
        try {
            Gson gson = new Gson();
            String json = gson.toJson(this);
            return gson.fromJson(json, VodInfo.class);
        } catch (Exception ignored) {
        }
        return this;
    }

    //takagen99
    public boolean isSeriesEmpty() {
        return seriesMap == null ? true : seriesMap.isEmpty();
    }

    public List<VodSeries> getFlagSeries(String playFlag) {
        List<VodSeries> list = null;
        if (!isSeriesEmpty()) {
            list = seriesMap.get(playFlag);
        } else {
            list = new ArrayList<>();
        }
        return list;
    }

    public boolean isFlagSeriesEmpty(String playFlag) {
        List<VodSeries> list = getFlagSeries(playFlag);
        return list.isEmpty();
    }

    public VodSeries getVodSeries(String playFlag, int playIndex) {
        VodSeries vodSeries = null;
        List<VodSeries> list = getFlagSeries(playFlag);
        if (list != null && !list.isEmpty()) {
            if (playIndex >= 0 && playIndex < list.size()) {
                vodSeries = list.get(playIndex);
            }
        }
        return vodSeries;
    }
    
}
