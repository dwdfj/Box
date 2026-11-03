package com.github.tvbox.osc.music;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 小贾影视仓 v27.13: LRC 歌词解析 —— 支持一行多时间戳([00:12.34][01:20.00]词)与
 * 毫秒/百分秒两种精度; 产出按时间升序的列表。解析失败返回空列表(UI 显示"暂无歌词")。
 */
public final class LrcParser {

    /** 一行歌词: 起始毫秒 + 文本。 */
    public static class Line {
        public final long timeMs;
        public final String text;

        Line(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }
    }

    /** 匹配 [mm:ss.xx] / [mm:ss.xxx] / [mm:ss] */
    private static final Pattern TAG = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");

    private LrcParser() {
    }

    public static List<Line> parse(String raw) {
        List<Line> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String row : raw.split("\\r?\\n")) {
            if (row == null) continue;
            String line = row.trim();
            if (line.isEmpty()) continue;
            Matcher m = TAG.matcher(line);
            List<Long> stamps = new ArrayList<>();
            int lastEnd = -1;
            while (m.find()) {
                long min = safeLong(m.group(1));
                long sec = safeLong(m.group(2));
                String frac = m.group(3);
                long ms = 0;
                if (frac != null) {
                    // 两位=百分秒, 三位=毫秒
                    ms = frac.length() == 3 ? safeLong(frac)
                            : frac.length() == 2 ? safeLong(frac) * 10
                            : frac.length() == 1 ? safeLong(frac) * 100 : 0;
                }
                stamps.add(min * 60000 + sec * 1000 + ms);
                lastEnd = m.end();
            }
            if (stamps.isEmpty() || lastEnd < 0) continue;
            String text = line.substring(lastEnd).trim();
            if (text.isEmpty()) continue;
            for (long t : stamps) out.add(new Line(t, text));
        }
        Collections.sort(out, (a, b) -> Long.compare(a.timeMs, b.timeMs));
        return out;
    }

    /** 当前播放位置应高亮的行下标; 全未到返回 -1。 */
    public static int indexOf(List<Line> lines, long posMs) {
        if (lines == null || lines.isEmpty()) return -1;
        int lo = 0, hi = lines.size() - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).timeMs <= posMs) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }

    private static long safeLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (Throwable t) {
            return 0;
        }
    }
}
