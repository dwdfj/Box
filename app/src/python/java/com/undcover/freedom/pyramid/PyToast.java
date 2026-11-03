package com.undcover.freedom.pyramid;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;


/**
 * Created by UndCover on 16/9/7.
 *
 * 小贾影视仓 fix: py 加载/插件下载都在后台线程执行(PythonSpider.depPool / 加载线程),
 * 原实现直接 Toast.makeText().show() 在后台线程会抛
 * "Can't toast on a thread that has not called Looper.prepare()",
 * 该异常会中断 py 加载并冒泡成 "py 加载失败"。
 * 现统一 post 到主线程显示, 任意线程调用都安全。
 */
public class PyToast {
    private static Toast innerToast;
    private static Context mContext;
    private static PyToast sInstance;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());


    public static void init(Context context) {
        mContext = context;
    }

    /**
     * 在Application中 用于初始化
     *
     * @return
     */
    public static PyToast getInstance() {
        if (sInstance == null) {
            synchronized (PyToast.class) {
                if (sInstance == null) {
                    sInstance = new PyToast();
                }
            }
        }
        return sInstance;
    }

    public static void showCancelableToast(String msg) {
        showCancelableToast(msg, Toast.LENGTH_SHORT);
    }

    /**
     * 快速显示Toast,无需排队等待
     *
     * @param msg
     * @param duration
     */
    public static void showCancelableToast(String msg, int duration) {
        final Context ctx = mContext;
        if (ctx == null) return;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (innerToast != null) {
                        innerToast.cancel();
                    }
                    innerToast = Toast.makeText(ctx, msg, duration);
                    innerToast.show();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    public static void showMessage(String msg, int duration) {
        final Context ctx = mContext;
        if (ctx == null) return;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(ctx, msg, duration).show();
                } catch (Throwable ignored) {
                }
            }
        });
    }
}
