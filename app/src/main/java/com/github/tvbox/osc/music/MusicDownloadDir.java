package com.github.tvbox.osc.music;

import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.util.List;

/**
 * 小贾影视仓 v27.25: 歌曲下载目录(用户自选)。
 *
 * Android 10+ 分区存储下 App 不能随便往公共 Music/ 里写, 所以这里走 SAF:
 * 用户在播放页点「目录」→ 系统文件选择器挑一个文件夹 → 拿到 tree Uri → 持久化授权。
 * 之后下载就用 DocumentsContract 在这个目录里建文件, 完全不需要存储权限。
 * 没设过就退回默认目录(公共 Music/小贾影视仓 或 App 专属目录, 见 MusicDownloader)。
 */
public final class MusicDownloadDir {

    private static final String PREF = "xj_music_cfg";
    private static final String KEY = "dl_tree";

    private MusicDownloadDir() {
    }

    /** 已选目录的 tree Uri 字符串; 未设置返回 null。 */
    public static String treeUri() {
        try {
            String s = AppContextHolder.get().getSharedPreferences(PREF, 0).getString(KEY, null);
            return (s == null || s.isEmpty()) ? null : s;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 目录显示名 —— 取 Uri 末段解码, 拿不到就返回 "自选目录"。 */
    public static String label() {
        String s = treeUri();
        if (s == null) return null;
        try {
            String tail = Uri.decode(s);
            int idx = tail.lastIndexOf('/');
            if (idx < 0) return "自选目录";
            String name = tail.substring(idx + 1);
            int colon = name.indexOf(':');
            if (colon >= 0) name = name.substring(colon + 1);
            int slash = name.lastIndexOf('/');
            if (slash >= 0) name = name.substring(slash + 1);
            return name.isEmpty() ? "自选目录" : name;
        } catch (Throwable t) {
            return "自选目录";
        }
    }

    /**
     * 记住用户选的目录并持久化读/写授权。
     * 注意: takePersistableUriPermission 必须在拿到结果的当次调用, 否则重启后失效。
     */
    public static void save(Context ctx, Intent data) {
        if (ctx == null || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            ctx.getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (Throwable ignored) {
        }
        try {
            ctx.getSharedPreferences(PREF, 0).edit().putString(KEY, uri.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 清除自选目录, 回到默认保存位置。 */
    public static void clear() {
        try {
            AppContextHolder.get().getSharedPreferences(PREF, 0).edit().remove(KEY).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 已持久化的授权是否还在(用户可能去系统设置里撤销)。 */
    public static boolean hasPermission() {
        String s = treeUri();
        if (s == null) return false;
        try {
            List<UriPermission> list = AppContextHolder.get().getContentResolver().getPersistedUriPermissions();
            for (UriPermission p : list) {
                if (p.getUri().toString().equals(s) && p.isWritePermission()) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 打开系统文件夹选择器。 */
    public static void pick(android.app.Activity act, int reqCode) {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            act.startActivityForResult(i, reqCode);
        } catch (Throwable t) {
            try {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                act.startActivityForResult(i, reqCode);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 把 tree Uri 解析成「可在其下建文件」的目录 document Uri。 */
    static Uri dirDocumentUri(String treeStr) {
        try {
            Uri tree = Uri.parse(treeStr);
            String docId = DocumentsContract.getTreeDocumentId(tree);
            return DocumentsContract.buildDocumentUriUsingTree(tree, docId);
        } catch (Throwable t) {
            return null;
        }
    }
}
