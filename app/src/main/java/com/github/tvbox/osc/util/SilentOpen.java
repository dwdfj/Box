package com.github.tvbox.osc.util;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.lifecycle.ViewModelProvider;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.ui.activity.FastSearchActivity;
import com.github.tvbox.osc.viewmodel.SourceViewModel;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 小贾影视仓 v27.28: 「按片名静默打开」。
 *
 * <p>为什么需要它: 首页推荐/分类网格里有一部分条目天生没有 id(豆瓣兜底数据、banner 数据、
 * 站点给的 msearch: 伪 id)。旧逻辑遇到这种条目直接拉 SearchActivity —— 用户看到的就是
 * "点一下封面, 先闪一下搜索页", 反馈过多次。
 *
 * <p>现在改成: 拿片名在当前站点做一次**静默搜索**(不弹任何页面), 命中就直接进详情。
 *
 * <p>v27.29: 静默搜索落空时不再只弹一句"没找到"(用户看到的就是死路) —— 自动转到
 * 「全网搜索」页并带着片名直接开搜。首页"站点推荐"里其实混着豆瓣兜底数据(没有 id、
 * 也不在当前线路的库里), 只查当前站永远查不到, 必须放开到全部站点。
 */
public class SilentOpen {

    private static final long CLICK_GAP = 1200L;   // 防连点重复匹配
    private static final long TIMEOUT = 8000L;     // 静默搜索兜底超时(超时直接转全网搜索, 不再干等)

    private static final AtomicLong lastTs = new AtomicLong(0L);

    public static void openByName(final Activity activity, final String sourceKey, final String name) {
        if (activity == null || activity.isFinishing()) return;
        final String wd = name == null ? "" : name.trim();
        if (TextUtils.isEmpty(wd)) return;
        long now = System.currentTimeMillis();
        if (now - lastTs.get() < CLICK_GAP) return;
        lastTs.set(now);

        final String key = resolveKey(sourceKey);
        if (TextUtils.isEmpty(key)) {
            openSearch(activity, wd, "当前站点还没准备好，已为你全网搜索");
            return;
        }
        toast(activity, "正在匹配「" + wd + "」…");

        final AtomicBoolean finished = new AtomicBoolean(false);
        final Handler main = new Handler(Looper.getMainLooper());
        final SourceViewModel vm;
        try {
            // ViewModel 必须在主线程取(ViewModelStore 不是线程安全的), 再交给后台线程跑搜索
            vm = new ViewModelProvider((androidx.lifecycle.ViewModelStoreOwner) activity)
                    .get(SourceViewModel.class);
        } catch (Throwable th) {
            openSearch(activity, wd, "已为你全网搜索");
            return;
        }
        final Runnable timeout = new Runnable() {
            @Override
            public void run() {
                if (finished.compareAndSet(false, true)) {
                    openSearch(activity, wd, "当前站点没有「" + wd + "」，已为你全网搜索");
                }
            }
        };
        main.postDelayed(timeout, TIMEOUT);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    vm.getQuickSearch(key, wd, new SourceViewModel.QuickSearchCallback() {
                        @Override
                        public void done(AbsXml data) {
                            if (!finished.compareAndSet(false, true)) return;
                            main.removeCallbacks(timeout);
                            final Movie.Video best = pick(data, wd);
                            main.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (best == null) {
                                        openSearch(activity, wd, "当前站点没有「" + wd + "」，已为你全网搜索");
                                    } else {
                                        openDetail(activity, best, wd);
                                    }
                                }
                            });
                        }
                    });
                } catch (Throwable th) {
                    if (finished.compareAndSet(false, true)) {
                        main.removeCallbacks(timeout);
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                openSearch(activity, wd, "已为你全网搜索");
                            }
                        });
                    }
                }
            }
        }, "xiaojia-silent-open").start();
    }

    /** 优先用条目自带的站点; 豆瓣兜底数据没有站点时用当前首页站点 */
    private static String resolveKey(String sourceKey) {
        try {
            if (!TextUtils.isEmpty(sourceKey)) {
                SourceBean sb = ApiConfig.get().getSource(sourceKey);
                if (sb != null && !TextUtils.isEmpty(sb.getKey())) return sb.getKey();
            }
            SourceBean home = ApiConfig.get().getHomeSourceBean();
            if (home != null) return home.getKey();
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 先要同名, 再要包含关系, 最后退而取第一条 —— 宁可进错也别把用户挡在门外 */
    private static Movie.Video pick(AbsXml data, String wd) {
        if (data == null || data.movie == null || data.movie.videoList == null) return null;
        String target = norm(wd);
        Movie.Video contains = null;
        for (Movie.Video v : data.movie.videoList) {
            if (v == null || TextUtils.isEmpty(v.id)) continue;
            String n = norm(v.name);
            if (n.isEmpty()) continue;
            if (n.equals(target)) return v;
            // v27.29: "兰香如故" vs "兰香如故(2026)"/"兰香如故 第01集" 这类也要认
            if (contains == null && (n.contains(target) || target.contains(n))) contains = v;
        }
        if (contains != null) return contains;
        for (Movie.Video v : data.movie.videoList) {
            if (v != null && !TextUtils.isEmpty(v.id)) return v;
        }
        return null;
    }

    /** 去掉空格/标点/括号年份, 只留可比的字 —— 源站片名常带副标题与集数 */
    private static String norm(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) sb.append(Character.toLowerCase(c));
        }
        String out = sb.toString();
        // 纯数字尾巴(年份/集数)去掉, 避免 2026 之类干扰
        int end = out.length();
        while (end > 0 && Character.isDigit(out.charAt(end - 1))) end--;
        return end > 0 ? out.substring(0, end) : out;
    }

    /** 当前站点确实没有 → 转「全网搜索」并带着片名直接开搜(不是一闪而过的中转页, 是真正的结果页) */
    private static void openSearch(final Activity activity, final String wd, final String tip) {
        toast(activity, tip);
        try {
            Intent intent = new Intent(activity, FastSearchActivity.class);
            intent.putExtra("title", wd);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    private static void openDetail(Activity activity, Movie.Video v, String wd) {
        try {
            Intent intent = new Intent(activity, DetailActivity.class);
            intent.putExtra("id", v.id);
            intent.putExtra("sourceKey", TextUtils.isEmpty(v.sourceKey) ? resolveKey(null) : v.sourceKey);
            intent.putExtra("title", TextUtils.isEmpty(v.name) ? wd : v.name);
            intent.putExtra("pic", v.pic);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Throwable th) {
            toast(activity, "打开失败，请重试");
        }
    }

    private static void toast(final Activity activity, final String msg) {
        try {
            if (activity == null || activity.isFinishing()) return;
            if (Looper.myLooper() == Looper.getMainLooper()) {
                Toast.makeText(App.getInstance(), msg, Toast.LENGTH_SHORT).show();
            } else {
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(App.getInstance(), msg, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        } catch (Throwable ignored) {
        }
    }
}
