package com.github.tvbox.osc.util;

import com.orhanobut.hawk.Hawk;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * 小贾影视仓 v27.31 智能内核 —— 自动在 IJK / Exo 之间选「起播更快」的那条。
 *
 * <p>为什么要它：两条内核的强项本来就不同。
 * <ul>
 *   <li>Exo(Media3)：HLS/DASH 走原生分片下载 + 自己的缓冲策略，同样的 m3u8 比 IJK 的 FFmpeg
 *       链路起播快 0.5~2 秒 —— OK影视 全程用 Exo，所以「看起来更快」。</li>
 *   <li>IJK(FFmpeg)：封装/编码兼容性最好，rmvb、非标 TS、部分 mkv 只有它能放，野源上活得下来。</li>
 * </ul>
 * 所以固定任何一条都是「一部分片快、一部分片废」。这里按 <b>站点 + 流类型</b> 记录两条内核的
 * 实测起播耗时和失败次数，每次起播选期望最优的那条；万一选错(超时/报错)当场换另一条重试，
 * 并把这次失败记进去 —— 同一个站点同一个类型下次直接选对。
 *
 * <p>记录只写本机 Hawk，不联网、不上传。样本很少时按类型先验猜：
 * m3u8/mpd 用 Exo，其余用 IJK。
 */
public class PlayerAuto {

    /** 播放器配置里的「智能」取值(见 PlayerHelper.getPlayerName) */
    public static final int TYPE_AUTO = 4;

    public static final int K_IJK = 1;
    public static final int K_EXO = 2;

    /** 流类型 */
    public static final int T_UNKNOWN = 0;
    public static final int T_HLS = 1;
    public static final int T_DASH = 2;
    public static final int T_FILE = 3;

    private static final String KEY_STATS = "player_auto_stats_v1";
    private static final int MAX_KEYS = 80;
    /** 失败一次的罚时(ms)：一次放不出来 ≈ 白等 15 秒 */
    private static final long FAIL_PENALTY = 15000L;

    private static JSONObject sCache;

    public static boolean isAuto(int playType) {
        return playType == TYPE_AUTO;
    }

    public static int urlType(String url) {
        if (url == null) return T_UNKNOWN;
        String u = url.toLowerCase();
        int q = u.indexOf('?');
        if (q > 0) u = u.substring(0, q);
        if (u.contains(".m3u8") || u.contains("/m3u8")) return T_HLS;
        if (u.contains(".mpd") || u.contains("type=mpd")) return T_DASH;
        if (u.contains(".mp4") || u.contains(".mkv") || u.contains(".mov") || u.contains(".flv")
                || u.contains(".avi") || u.contains(".ts") || u.contains(".rmvb")
                || u.contains(".webm") || u.contains(".m4v") || u.contains(".wmv")) return T_FILE;
        return T_UNKNOWN;
    }

    /**
     * 选内核。返回 {@link #K_IJK} 或 {@link #K_EXO}。
     *
     * @param sourceKey 站点 key（只用来区分「这个站的线路习惯」，可为空）
     */
    public static int pick(String sourceKey, String url) {
        int type = urlType(url);
        if (type == T_DASH) return K_EXO;           // DASH 只有 Exo 这条链路
        try {
            Stat ijk = read(sourceKey, type, K_IJK);
            Stat exo = read(sourceKey, type, K_EXO);
            // 两条都攒够了样本：直接比「期望耗时」(起播耗时 + 失败率罚时)
            if (ijk.n >= 2 && exo.n >= 2) {
                return score(ijk) <= score(exo) ? K_IJK : K_EXO;
            }
            // 只有一条有样本：它不慢、不怎么失败就用它
            if (ijk.n >= 2 && ijk.failRate() <= 0.34 && ijk.avgMs() < 9000) return K_IJK;
            if (exo.n >= 2 && exo.failRate() <= 0.34 && exo.avgMs() < 9000) return K_EXO;
            // 样本还少但已经翻过车：躲开它
            if (ijk.n >= 1 && ijk.failRate() >= 0.5) return K_EXO;
            if (exo.n >= 1 && exo.failRate() >= 0.5) return K_IJK;
        } catch (Throwable ignored) {
        }
        return guess(type);
    }

    public static void recordSuccess(String sourceKey, String url, int kernel, long ms) {
        record(sourceKey, url, kernel, ms, true);
    }

    public static void recordFail(String sourceKey, String url, int kernel, long ms) {
        record(sourceKey, url, kernel, ms, false);
    }

    /**
     * 某个站点/流类型现在的选择倾向，给设置页做说明用。
     */
    public static String describe(String sourceKey, int type) {
        try {
            Stat ijk = read(sourceKey, type, K_IJK);
            Stat exo = read(sourceKey, type, K_EXO);
            if (ijk.n == 0 && exo.n == 0) return "暂无实测数据";
            return "IJK 均 " + ijk.avgMs() + "ms/" + ijk.n + "次(失败" + ijk.f + ")"
                    + "，Exo 均 " + exo.avgMs() + "ms/" + exo.n + "次(失败" + exo.f + ")";
        } catch (Throwable th) {
            return "";
        }
    }

    // ---------------------------------------------------------------- 内部

    private static int guess(int type) {
        // HLS：Exo 的分片管线一般先出画面
        if (type == T_HLS) return K_EXO;
        // 渐进式文件 / 未知：FFmpeg 兼容性最好
        return K_IJK;
    }

    private static long score(Stat s) {
        return s.avgMs() + (long) (s.failRate() * FAIL_PENALTY);
    }

    private static synchronized void record(String sourceKey, String url, int kernel, long ms, boolean ok) {
        if (kernel != K_IJK && kernel != K_EXO) return;
        int type = urlType(url);
        try {
            JSONObject stats = load();
            String key = keyOf(sourceKey, type);
            JSONObject node = stats.optJSONObject(key);
            if (node == null) node = new JSONObject();
            String k = String.valueOf(kernel);
            JSONObject s = node.optJSONObject(k);
            if (s == null) s = new JSONObject();
            int n = s.optInt("n", 0);
            int f = s.optInt("f", 0);
            long avg = s.optLong("ms", 0);
            if (ms <= 0 || ms > 60000) ms = avg > 0 ? avg : 3000;
            // 老样本权重 3:1 —— 一次网络抖动不该把长期结论推翻
            avg = avg <= 0 ? ms : (avg * 3 + ms) / 4;
            n++;
            if (!ok) f++;
            s.put("n", n);
            s.put("f", f);
            s.put("ms", avg);
            s.put("t", System.currentTimeMillis());
            node.put(k, s);
            stats.put(key, node);
            trim(stats);
            Hawk.put(KEY_STATS, stats.toString());
        } catch (Throwable ignored) {
        }
    }

    private static Stat read(String sourceKey, int type, int kernel) {
        Stat st = new Stat();
        try {
            JSONObject node = load().optJSONObject(keyOf(sourceKey, type));
            if (node == null) return st;
            JSONObject s = node.optJSONObject(String.valueOf(kernel));
            if (s == null) return st;
            st.n = s.optInt("n", 0);
            st.f = s.optInt("f", 0);
            st.sum = s.optLong("ms", 0);
        } catch (Throwable ignored) {
        }
        return st;
    }

    private static String keyOf(String sourceKey, int type) {
        return (sourceKey == null ? "" : sourceKey) + "|" + type;
    }

    private static JSONObject load() {
        if (sCache != null) return sCache;
        try {
            String raw = Hawk.get(KEY_STATS, "");
            sCache = (raw == null || raw.isEmpty()) ? new JSONObject() : new JSONObject(raw);
        } catch (Throwable th) {
            sCache = new JSONObject();
        }
        return sCache;
    }

    /** 只留最近用过的 MAX_KEYS 条，避免 Hawk 里无限膨胀 */
    private static void trim(JSONObject stats) {
        if (stats.length() <= MAX_KEYS) return;
        final List<String> keys = new ArrayList<>();
        Iterator<String> it = stats.keys();
        while (it.hasNext()) keys.add(it.next());
        Collections.sort(keys, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                long ta = stamp(stats, a);
                long tb = stamp(stats, b);
                if (ta == tb) return 0;
                return ta < tb ? -1 : 1;
            }
        });
        int drop = keys.size() - MAX_KEYS + 20;
        for (int i = 0; i < drop && i < keys.size(); i++) stats.remove(keys.get(i));
    }

    private static long stamp(JSONObject stats, String key) {
        JSONObject node = stats.optJSONObject(key);
        if (node == null) return 0;
        return Math.max(node.optJSONObject("1") == null ? 0 : node.optJSONObject("1").optLong("t", 0),
                node.optJSONObject("2") == null ? 0 : node.optJSONObject("2").optLong("t", 0));
    }

    private static class Stat {
        int n;      // 样本数
        int f;      // 失败数
        long sum;   // 平均起播耗时(ms)

        double failRate() {
            return n <= 0 ? 0 : (double) f / (double) n;
        }

        long avgMs() {
            return sum;
        }
    }
}
