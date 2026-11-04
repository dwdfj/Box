package com.github.tvbox.osc.music;

import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 小贾影视仓 v27.13: 歌曲下载 —— 用当前线路现取直链, 流式写入
 * 外部存储 Music/小贾影视仓/ 目录(歌曲名-歌手.ext); 取不到直链时报错, 不缓存脏数据。
 */
public final class MusicDownloader {
    private static final String TAG = "MusicDownloader";
    private static final String UA = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    public interface Cb {
        /** progress 0~1, done 时 path 非空。 */
        void onProgress(float progress);

        void onDone(String path);

        void onFail(String err);
    }

    private MusicDownloader() {
    }

    public static void download(final MusicApi.Song song, final String quality, final Cb cb) {
        if (song == null || song.songmid == null) {
            cb.onFail("无效歌曲");
            return;
        }
        MusicDirect.fetch(MusicPlayer.get().line(), song.songmid, quality, (src, url, err) -> {
            if (url == null) {
                cb.onFail("取链失败: " + (err == null ? "无地址" : err));
                return;
            }
            new Thread(() -> save(song, url, cb)).start();
        });
    }

    private static void save(MusicApi.Song song, String url, Cb cb) {
        FileOutputStream fos = null;
        Response resp = null;
        try {
            String ext = url.contains(".flac") ? ".flac" : url.contains(".m4a") ? ".m4a" : ".mp3";
            // v27.24: 不再只写公共 Music/ 目录 —— Android 10+ 分区存储下 getExternalStoragePublicDirectory
            // 没权限时 mkdirs 直接失败, 用户看到的就是「无法创建目录」。
            // 策略: 有权限就存公共 Music/小贾影视仓(文件管理器好找), 没权限自动落到应用专属目录
            //       Android/data/<包名>/files/Music/小贾影视仓/
            File dir = null;
            try {
                File pub = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_MUSIC), "小贾影视仓");
                if (pub.exists() || pub.mkdirs()) dir = pub;
            } catch (Throwable ignored) {
            }
            if (dir == null) {
                android.content.Context ctx = AppContextHolder.get();
                File baseDir = ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
                if (baseDir == null) baseDir = new File(ctx.getFilesDir(), "Music");
                dir = new File(baseDir, "小贾影视仓");
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new java.io.IOException("无法创建目录: " + dir.getAbsolutePath());
                }
            }
            String base = (song.name == null ? "未知" : song.name)
                    + (song.singer == null || song.singer.isEmpty() ? "" : "-" + song.singer);
            base = base.replaceAll("[\\\\/:*?\"<>|]", "_");
            File out = new File(dir, base + ext);

            Request req = new Request.Builder().url(url).header("User-Agent", UA).get().build();
            resp = HTTP.newCall(req).execute();
            if (!resp.isSuccessful() || resp.body() == null)
                throw new java.io.IOException("HTTP " + resp.code());
            long total = resp.body().contentLength();
            InputStream is = resp.body().byteStream();
            fos = new FileOutputStream(out);
            byte[] buf = new byte[16384];
            long got = 0;
            int n;
            int lastPct = -1;
            while ((n = is.read(buf)) > 0) {
                fos.write(buf, 0, n);
                got += n;
                if (total > 0) {
                    int pct = (int) (got * 100 / total);
                    if (pct != lastPct && pct % 5 == 0) {
                        lastPct = pct;
                        final float f = got / (float) total;
                        cb.onProgress(f);
                    }
                }
            }
            fos.flush();
            final String path = out.getAbsolutePath();
            Log.d(TAG, "saved " + path + " size=" + got);
            cb.onDone(path);
        } catch (Throwable t) {
            Log.w(TAG, "download fail", t);
            cb.onFail(String.valueOf(t.getMessage()));
        } finally {
            try {
                if (fos != null) fos.close();
            } catch (Throwable ignored) {
            }
            if (resp != null) resp.close();
        }
    }
}
