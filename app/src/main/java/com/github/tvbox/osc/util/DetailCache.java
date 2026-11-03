package com.github.tvbox.osc.util;

import com.github.tvbox.osc.base.App;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Comparator;

/**
 * 小贾影视仓 v27.16 —— 详情(detailContent)落盘缓存。
 *
 * <p>为什么做: 点封面直达播放页后, 每次都要先等源站返回详情(JSON 线路 0.3~2s,
 * py 源冷启动 5~40s), 然后才起播。同一部片短时间内重复点开(退出再进、从历史再进、
 * 换集回来)完全是白等 —— 详情里的线路/集数在几分钟内基本不变。</p>
 *
 * <p>做法: 缓存**源站原始响应体**(不是解析后的对象), 命中时走与网络返回完全相同的解析路径
 * (SourceViewModel.json/xml), 所以下游行为一模一样, 不存在"缓存对象字段缺失"的风险。</p>
 *
 * <p>时效: 30 分钟。超过即视为过期并删除(源站补新集通常不会在半小时内发生,
 * 而 URL/线路变更在这个窗口外一律重新拉)。</p>
 */
public class DetailCache {

    /** 10 分钟。够覆盖"退出再进 / 从历史再进 / 换集回来", 又不至于让签名过期的播放地址长期滞留 */
    private static final long TTL_MS = 10L * 60L * 1000L;
    /** 单条超过 512KB 不落盘 —— 详情体一般 3~80KB, 过大的多半是异常响应 */
    private static final int MAX_BYTES = 512 * 1024;
    /** 目录内最多保留的文件数, 超出删最旧的 */
    private static final int MAX_FILES = 300;

    private static File dir() {
        try {
            File d = new File(App.getInstance().getCacheDir(), "dcache");
            if (!d.exists()) d.mkdirs();
            return d;
        } catch (Throwable th) {
            return null;
        }
    }

    private static File file(String sourceKey, String id) {
        File d = dir();
        if (d == null) return null;
        String name = "d_" + MD5.string2MD5((sourceKey == null ? "" : sourceKey) + "|" + (id == null ? "" : id)) + ".txt";
        return new File(d, name);
    }

    /**
     * 写缓存。isXml: 该源详情是 XML(type==0) 还是 JSON。
     * 只在真正拿到有效响应时调用, 空响应/错误响应不要写(否则会把"没数据"缓存 30 分钟)。
     */
    public static void put(String sourceKey, String id, boolean isXml, String payload) {
        if (payload == null) return;
        String body = payload.trim();
        if (body.length() < 16) return;
        if (!isXml && !body.startsWith("{")) return;      // JSON 源返回非 JSON(多半是错误页)
        if (isXml && !body.startsWith("<")) return;
        byte[] data = null;
        try {
            data = body.getBytes("UTF-8");
        } catch (Throwable th) {
            return;
        }
        if (data.length > MAX_BYTES) return;
        File f = file(sourceKey, id);
        if (f == null) return;
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(f, false);
            fos.write((System.currentTimeMillis() + " " + (isXml ? "0" : "1") + "\n").getBytes("UTF-8"));
            fos.write(data);
            fos.flush();
        } catch (Throwable th) {
            th.printStackTrace();
        } finally {
            try {
                if (fos != null) fos.close();
            } catch (Throwable ignored) {
            }
        }
        trim();
    }

    /**
     * 读缓存。返回 {kind, payload}: kind "0"=XML, "1"=JSON。未命中/过期返回 null。
     */
    public static String[] get(String sourceKey, String id) {
        File f = file(sourceKey, id);
        if (f == null || !f.exists() || f.length() <= 0) return null;
        try {
            byte[] buf = new byte[(int) f.length()];
            FileInputStream fis = new FileInputStream(f);
            int off = 0;
            while (off < buf.length) {
                int n = fis.read(buf, off, buf.length - off);
                if (n <= 0) break;
                off += n;
            }
            fis.close();
            String all = new String(buf, 0, off, "UTF-8");
            int nl = all.indexOf('\n');
            if (nl <= 0) return null;
            String head = all.substring(0, nl).trim();
            int sp = head.indexOf(' ');
            if (sp <= 0) return null;
            long ts = Long.parseLong(head.substring(0, sp));
            String kind = head.substring(sp + 1).trim();
            if (System.currentTimeMillis() - ts > TTL_MS) {
                f.delete();
                return null;
            }
            String payload = all.substring(nl + 1);
            if (payload.trim().length() < 16) return null;
            return new String[]{kind, payload};
        } catch (Throwable th) {
            return null;
        }
    }

    /** 播放失败时清掉该片缓存 —— 下次进来重新拉, 避免"缓存里是过期播放地址"反复失败 */
    public static void invalidate(String sourceKey, String id) {
        try {
            File f = file(sourceKey, id);
            if (f != null && f.exists()) f.delete();
        } catch (Throwable ignored) {
        }
    }

    /** 清理超出上限的旧文件(只在 put 时偶发触发, 不占启动时间) */
    private static void trim() {
        try {
            File d = dir();
            if (d == null) return;
            File[] fs = d.listFiles();
            if (fs == null || fs.length <= MAX_FILES) return;
            Arrays.sort(fs, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return Long.compare(a.lastModified(), b.lastModified());
                }
            });
            int del = fs.length - MAX_FILES;
            for (int i = 0; i < del; i++) fs[i].delete();
        } catch (Throwable ignored) {
        }
    }
}
