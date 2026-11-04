package com.github.tvbox.osc.music;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 小贾影视仓 v27.13: 歌曲下载 —— 用当前线路现取直链, 流式写入。
 *
 * v27.25 目录策略(按优先级):
 *   1) 用户在播放页「目录」里自选的文件夹(SAF tree Uri, 走 DocumentsContract, 免权限);
 *   2) 公共 Music/小贾影视仓(有权限时);
 *   3) App 专属 Android/data/<包名>/files/Music/小贾影视仓(必定可写)。
 * 文件名: 歌名-歌手.ext; 取不到直链直接报错, 不落脏文件。
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
        MusicDirect.fetch(MusicPlayer.get().line(), song.songmid, quality, song.name, song.singer,
                song.albumId, (src, url, err) -> {
            if (url == null) {
                cb.onFail("取链失败: " + (err == null ? "无地址" : err));
                return;
            }
            new Thread(() -> save(song, url, cb)).start();
        });
    }

    private static void save(MusicApi.Song song, String url, Cb cb) {
        Response resp = null;
        try {
            String ext = url.contains(".flac") ? ".flac" : url.contains(".m4a") ? ".m4a" : ".mp3";
            String base = (song.name == null ? "未知" : song.name)
                    + (song.singer == null || song.singer.isEmpty() ? "" : "-" + song.singer);
            base = base.replaceAll("[\\\\/:*?\"<>|]", "_");
            final String fileName = base + ext;
            final String mime = ".flac".equals(ext) ? "audio/flac"
                    : ".m4a".equals(ext) ? "audio/mp4" : "audio/mpeg";

            Request req = new Request.Builder().url(url).header("User-Agent", UA).get().build();
            resp = HTTP.newCall(req).execute();
            if (!resp.isSuccessful() || resp.body() == null)
                throw new java.io.IOException("HTTP " + resp.code());
            final long total = resp.body().contentLength();

            // ① 用户自选目录(SAF)
            String treeStr = MusicDownloadDir.treeUri();
            if (treeStr != null) {
                Uri dirDoc = MusicDownloadDir.dirDocumentUri(treeStr);
                if (dirDoc != null) {
                    try {
                        ContentResolver cr = AppContextHolder.get().getContentResolver();
                        Uri fileUri = DocumentsContract.createDocument(cr, dirDoc, mime, fileName);
                        if (fileUri != null) {
                            OutputStream os = cr.openOutputStream(fileUri);
                            if (os != null) {
                                pipe(resp, os, total, cb);
                                cb.onDone(Uri.decode(fileUri.toString()));
                                return;
                            }
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "SAF 写入失败, 回退默认目录: " + t.getMessage());
                    }
                }
            }

            // ②③ 默认目录
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
            File out = new File(dir, fileName);
            FileOutputStream fos = new FileOutputStream(out);
            pipe(resp, fos, total, cb);
            Log.d(TAG, "saved " + out.getAbsolutePath());
            cb.onDone(out.getAbsolutePath());
        } catch (Throwable t) {
            Log.w(TAG, "download fail", t);
            cb.onFail(String.valueOf(t.getMessage()));
        } finally {
            if (resp != null) resp.close();
        }
    }

    /** 流式落盘 + 每 5% 回报一次进度(调用方负责关流)。 */
    private static void pipe(Response resp, OutputStream os, long total, Cb cb) throws Exception {
        InputStream is = resp.body().byteStream();
        byte[] buf = new byte[16384];
        long got = 0;
        int n;
        int lastPct = -1;
        try {
            while ((n = is.read(buf)) > 0) {
                os.write(buf, 0, n);
                got += n;
                if (total > 0) {
                    int pct = (int) (got * 100 / total);
                    if (pct != lastPct && pct % 5 == 0) {
                        lastPct = pct;
                        cb.onProgress(got / (float) total);
                    }
                }
            }
            os.flush();
        } finally {
            try {
                os.close();
            } catch (Throwable ignored) {
            }
            try {
                is.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
