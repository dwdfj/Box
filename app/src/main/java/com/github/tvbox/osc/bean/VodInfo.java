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
                // v24.8: 同样换成 sortKey 装饰排序, 与选集排序共用一套规范 key
                java.util.Collections.sort(seriesFlags, (f1, f2) -> sortKey(f1.name).compareTo(sortKey(f2.name)));
            } catch (Throwable t) {
                t.printStackTrace();
            }
            for (VodSeriesFlag flag : seriesFlags) {
                List<VodSeries> list = tempSeriesMap.get(flag.name);
                assert list != null;
                // 小贾影视仓 v24.4: 选集一律数值自然升序(顺向)。旧版启发式太脆:
                // ①要求整条列表都是"统一前缀+数字+统一后缀"才排, 源里混一个异名(如"第01集"
                //   和"第1集"并存)就整条放弃; ②倒序检测只在线路数<=3 时生效。
                // 现在只要 60% 以上条目带数字就整条稳定自然升序; 纯文字列表(花絮/上中下)保留源顺序。
                sortSeriesNatural(list);
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

    /** 小贾影视仓 v24.7.3: 全角数字 ０-９ / 带圈数字 ①-⑳ -> 0-9; 其余返回 -1 */
    private static int digitValue(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= '\uFF10' && c <= '\uFF19') return c - '\uFF10'; // 全角 ０-９
        int cd = circledDigit(c);
        return cd > 0 ? cd : -1;
    }

    private static long readNum(String s, int[] pos) {
        long v = 0;
        int d = digitValue(s.charAt(pos[0]));
        if (d >= 0) {
            v = d;
            pos[0]++;
        }
        while (pos[0] < s.length()) {
            d = digitValue(s.charAt(pos[0]));
            if (d < 0) break;
            v = v * 10 + d;
            pos[0]++;
            if (v > 99999999) v = 99999999; // 防溢出
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
     * 小贾影视仓 v24.8: 装饰排序(decorate-sort-undecorate) key。
     * 把名称转成"数字段展开为 \\u0001 + 8位定宽数字 + \\u0002、其余字符原样"的规范串,
     * 之后直接 String.compareTo —— 定宽数字段的字典序 == 数值序(第2集 < 第10集),
     * 全角/带圈数字统一折算。关键收益: String.compareTo 永不违反 TimSort 的
     * "comparison method violates its general contract", 旧版 naturalCompare 在
     * 混合命名(数字段与字符段交错)列表上偶发违反传递性 -> Collections.sort 抛
     * IllegalArgumentException -> 被上层 catch 吞掉 -> 列表保持源顺序 = **排序从未生效**,
     * 这就是"改了好几次剧级还是不依次排列"的真正根因。
     */
    public static String sortKey(String name) {
        if (name == null) name = "";
        StringBuilder sb = new StringBuilder(name.length() + 16);
        int i = 0;
        while (i < name.length()) {
            char c = name.charAt(i);
            if (isNumChar(c)) {
                int[] pos = {i};
                long v = readNum(name, pos);
                sb.append('\u0001');
                // readNum 上限 99999999, 8 位定宽足够
                String num = Long.toString(v);
                for (int pad = num.length(); pad < 8; pad++) sb.append('0');
                sb.append(num).append('\u0002');
                i = pos[0];
            } else {
                sb.append(Character.toLowerCase(c));
                i++;
            }
        }
        return sb.toString();
    }

    /** 小贾影视仓 v24.8: 剧集/线路统一比较器 —— 基于 sortKey 的字典序, 语义与 naturalCompare 等价但绝对安全。 */
    public static java.util.Comparator<VodSeries> seriesComparator() {
        return (a, b) -> sortKey(a == null || a.name == null ? "" : a.name)
                .compareTo(sortKey(b == null || b.name == null ? "" : b.name));
    }

    /**
     * 小贾影视仓 v24.4: 选集自然升序 —— 列表里 60% 以上条目带数字(阿拉伯数字或带圈数字)时,
     * 整条稳定升序重排(第2集 < 第10集, ① < ②, 倒序源自动转正向);
     * 纯文字列表(花絮/预告/上中下)保留源顺序。稳定排序保证同键条目相对位置不变。
     * 小贾影视仓 v24.8: 比较器换成 sortKey 装饰排序, 根治 TimSort contract 异常导致排序静默失效。
     * 小贾影视仓 v24.9: **去掉 60% 门槛** —— 用户反馈"多线路的剧集仍不是顺向依次排列"。
     * 旧门槛要求整条列表 60% 以上条目带数字才排序, 但很多源的集名是"1. 第一集""01【更新】"
     * 这类混排, 只要有 3 条纯文字(正片/预告/花絮)就跌破门槛整条放弃排序 = 保持源顺序。
     * 现在只要**有任意一条带数字**就整条排序; 纯文字列表(一条数字都没有)仍保留源顺序。
     * 另外改为"排序到新 list 再回填", 不再对 seriesMap 里的原 list 做原地 Collections.sort ——
     * 该 list 若来自 unmodifiable 包装会抛 UnsupportedOperationException 被 catch 吞掉(排序静默失效)。
     */
    private void sortSeriesNatural(List<VodSeries> list) {
        try {
            int n = list.size();
            if (n < 2 || n > 2000) return;
            int numCount = 0;
            for (VodSeries s : list) {
                if (s != null && s.name != null && nameHasNumber(s.name)) numCount++;
            }
            if (numCount == 0) return; // 纯文字列表(花絮/预告/上中下)保留源顺序
            // 装饰排序: 生成新 list 排序后回填, 避免对可能不可变的原 list 原地排序失败
            List<VodSeries> sorted = new ArrayList<>(list);
            java.util.Collections.sort(sorted, seriesComparator());
            list.clear();
            list.addAll(sorted);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private boolean nameHasNumber(String name) {
        // 小贾影视仓 v24.7.3: 正则 \d 只认 ASCII 数字, 中文源常见的全角数字(第０１集)会漏判,
        // 导致整条列表被判成"纯文字"跳过排序。改为逐字符检测(ASCII/全角/带圈)。
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= '\uFF10' && c <= '\uFF19') || circledDigit(c) > 0) {
                return true;
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
