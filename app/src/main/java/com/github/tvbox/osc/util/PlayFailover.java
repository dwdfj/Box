package com.github.tvbox.osc.util;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.lifecycle.Observer;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.orhanobut.hawk.Hawk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 小贾影视仓 v16 —— 播放失败自动换源(借鉴 FongMi/TV)。
 *
 * <p>当前线路重试(含切换播放内核)仍然失败时, 后台跨源搜索同名影片,
 * 命中后取该源详情并定位到同一集继续播放, 而不是直接报错退出。
 * PlayActivity(全屏播放) 与 PlayFragment(详情页预览播放) 共用本类。</p>
 *
 * <p>使用方式: 宿主实现 {@link Host}, 在初始化时 {@link #attach()},
 * 播放失败时调用 {@link #start(VodInfo, String)}, 换集时 {@link #resetRuntime()},
 * 销毁时 {@link #destroy()}。</p>
 */
public class PlayFailover implements Observer<AbsXml> {

    public interface Host {
        /** 宿主持有的 SourceViewModel(用于发起搜索/详情请求) */
        SourceViewModel sourceViewModel();

        /** 播放器上的提示 */
        void failoverTip(String msg, boolean loading, boolean err);

        /** 换源成功, 宿主需替换 VodInfo/线路并继续播放 */
        void failoverSwitch(VodInfo info, String sourceName);
    }

    private static final int MAX_SOURCES = 8;
    private static final long SEARCH_TIMEOUT = 9000L;

    private final Host host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Movie.Video> candidates = new ArrayList<>();
    private final HashSet<String> seen = new HashSet<>();
    private final Runnable timeoutTask = new Runnable() {
        @Override
        public void run() {
            if (searching) {
                searching = false;
                pending = false;
                host.failoverTip("当前线路播放失败，未找到其他可用线路", false, true);
            }
        }
    };

    private ExecutorService pool;
    private boolean attached = false;
    private VodInfo curInfo;
    private String curKey;
    private int index = 0;
    private int tried = 0;
    private int searchTotal = 0;
    private String pendingKey;
    private String pendingId;
    private final java.util.concurrent.atomic.AtomicInteger searchDone = new java.util.concurrent.atomic.AtomicInteger(0);
    private volatile boolean searching = false;
    private volatile boolean pending = false;

    public PlayFailover(Host host) {
        this.host = host;
    }

    public void attach() {
        if (attached) return;
        SourceViewModel vm = host.sourceViewModel();
        if (vm == null) return;
        vm.detailResult.observeForever(this);
        attached = true;
    }

    public void destroy() {
        handler.removeCallbacks(timeoutTask);
        if (pool != null) {
            try {
                pool.shutdownNow();
            } catch (Throwable ignored) {
            }
            pool = null;
        }
        if (attached) {
            SourceViewModel vm = host.sourceViewModel();
            if (vm != null) vm.detailResult.removeObserver(this);
            attached = false;
        }
    }

    /** 换集/重播时重置换源瞬时状态(候选缓存与已试源保留) */
    public void resetRuntime() {
        searching = false;
        pending = false;
        handler.removeCallbacks(timeoutTask);
    }

    /** 切换影片时彻底重置(清理候选与已试源, 避免跨片串味) */
    public void resetAll() {
        resetRuntime();
        synchronized (candidates) {
            candidates.clear();
        }
        seen.clear();
        index = 0;
        tried = 0;
        curInfo = null;
        curKey = null;
    }

    /** 播放失败入口。返回 true 表示已接管(正在搜索/正在切换), 调用方不要再报错。 */
    public boolean start(VodInfo info, String srcKey) {
        if (!enabled()) return false;
        if (info == null || TextUtils.isEmpty(info.name)) return false;
        curInfo = info;
        curKey = !TextUtils.isEmpty(srcKey) ? srcKey : info.sourceKey;
        if (tried >= MAX_SOURCES) return false;
        if (pending || searching) return true;
        if (tryNext()) return true;
        if (!hasMoreSources()) return false;
        searching = true;
        host.failoverTip("当前线路播放失败，正在自动切换其他线路…", true, false);
        search();
        handler.removeCallbacks(timeoutTask);
        handler.postDelayed(timeoutTask, SEARCH_TIMEOUT);
        return true;
    }

    private boolean enabled() {
        try {
            return Hawk.get(HawkConfig.PLAY_FAILOVER, true);
        } catch (Throwable th) {
            return true;
        }
    }

    private boolean hasMoreSources() {
        try {
            List<SourceBean> all = ApiConfig.get().getSourceBeanList();
            if (all == null) return false;
            HashMap<String, String> checked = SearchHelper.getSourcesForSearch();
            for (SourceBean bean : all) {
                if (bean == null || !bean.isSearchable()) continue;
                String key = bean.getKey();
                if (key == null || key.equals(curKey)) continue;
                if (seen.contains(key)) continue;
                if (checked != null && checked.size() > 0 && !checked.containsKey(key)) continue;
                return true;
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return false;
    }

    /** 后台并发向其它源发起同名搜索(静默模式, 不污染详情页快速搜索面板) */
    private void search() {
        try {
            final String wd = curInfo.name;
            HashMap<String, String> checked = SearchHelper.getSourcesForSearch();
            List<SourceBean> all = ApiConfig.get().getSourceBeanList();
            if (all == null || all.isEmpty()) return;
            if (pool == null || pool.isShutdown()) pool = Executors.newFixedThreadPool(4);
            // 先确定要搜的源(定好总数再并发派发, 避免回调早于计数赋值)
            final List<String> keys = new ArrayList<>();
            for (SourceBean bean : all) {
                if (bean == null || !bean.isSearchable()) continue;
                String key = bean.getKey();
                if (key == null || key.equals(curKey)) continue;
                if (seen.contains(key)) continue;
                if (checked != null && checked.size() > 0 && !checked.containsKey(key)) continue;
                if (keys.size() >= MAX_SOURCES) break;
                keys.add(key);
            }
            searchTotal = keys.size();
            searchDone.set(0);
            if (keys.isEmpty()) {
                searching = false;
                handler.removeCallbacks(timeoutTask);
                host.failoverTip("当前线路播放失败，未找到其他可用线路", false, true);
                return;
            }
            for (final String key : keys) {
                seen.add(key);
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            SourceViewModel vm = host.sourceViewModel();
                            if (vm == null) {
                                markSearchProgress();
                                return;
                            }
                            vm.getQuickSearch(key, wd, new SourceViewModel.QuickSearchCallback() {
                                @Override
                                public void done(AbsXml data) {
                                    collect(data, key);
                                }
                            });
                        } catch (Throwable th) {
                            th.printStackTrace();
                            markSearchProgress();
                        }
                    }
                });
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /** 单个源搜索完成计数; 全部返回且无候选时立即给结论, 不必干等超时 */
    private void markSearchProgress() {
        boolean done = false;
        synchronized (this) {
            if (!searching) return;
            if (searchDone.incrementAndGet() >= searchTotal) {
                searching = false;
                done = true;
            }
        }
        if (!done) return;
        handler.removeCallbacks(timeoutTask);
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!tryNext()) host.failoverTip("当前线路播放失败，未找到其他可用线路", false, true);
            }
        });
    }

    private void collect(AbsXml data, String key) {
        boolean switchNow = false;
        try {
            if (data == null || data.movie == null || data.movie.videoList == null || data.movie.videoList.isEmpty()) return;
            String want = curInfo == null ? null : curInfo.name;
            Movie.Video best = null;
            for (Movie.Video v : data.movie.videoList) {
                if (v == null || TextUtils.isEmpty(v.id) || TextUtils.isEmpty(v.sourceKey)) continue;
                if (TextUtils.isEmpty(want) || TextUtils.isEmpty(v.name)) {
                    best = v;
                    break;
                }
                if (want.equals(v.name)) { // 精确同名优先
                    best = v;
                    break;
                }
                if (best == null && similarName(want, v.name)) best = v;
            }
            if (best == null) return;
            synchronized (candidates) {
                candidates.add(best);
            }
            synchronized (this) {
                if (searching) {
                    searching = false;
                    switchNow = true;
                }
            }
            if (switchNow) {
                handler.removeCallbacks(timeoutTask);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!tryNext()) host.failoverTip("当前线路播放失败，未找到其他可用线路", false, true);
                    }
                });
            }
        } catch (Throwable th) {
            th.printStackTrace();
        } finally {
            markSearchProgress();
        }
    }

    private boolean similarName(String a, String b) {
        if (TextUtils.isEmpty(a) || TextUtils.isEmpty(b)) return false;
        String x = a.replaceAll("[\\s·:：\\-—_()（）\\[\\]【】]", "");
        String y = b.replaceAll("[\\s·:：\\-—_()（）\\[\\]【】]", "");
        if (x.isEmpty() || y.isEmpty()) return false;
        return x.equals(y) || x.contains(y) || y.contains(x);
    }

    /** 取下一个候选源并请求详情 */
    private boolean tryNext() {
        Movie.Video target = null;
        synchronized (candidates) {
            while (index < candidates.size()) {
                Movie.Video v = candidates.get(index);
                index++;
                if (v == null || TextUtils.isEmpty(v.id) || TextUtils.isEmpty(v.sourceKey)) continue;
                if (v.sourceKey.equals(curKey)) continue;
                target = v;
                break;
            }
        }
        if (target == null) return false;
        tried++;
        pending = true;
        pendingKey = target.sourceKey;
        pendingId = target.id;
        host.failoverTip("当前线路播放失败，正在自动切换线路…", true, false);
        try {
            SourceViewModel vm = host.sourceViewModel();
            if (vm == null) {
                pending = false;
                return false;
            }
            vm.getDetail(target.sourceKey, target.id);
        } catch (Throwable th) {
            th.printStackTrace();
            pending = false;
            return false;
        }
        return true;
    }

    @Override
    public void onChanged(AbsXml absXml) {
        if (!pending) return;
        pending = false;
        try {
            if (absXml == null || absXml.movie == null || absXml.movie.videoList == null || absXml.movie.videoList.isEmpty()) {
                nextOrGiveUp();
                return;
            }
            Movie.Video v = absXml.movie.videoList.get(0);
            if (v == null) {
                nextOrGiveUp();
                return;
            }
            // 以请求参数为准(部分源详情返回的 id 与搜索 id 不一致)
            if (!TextUtils.isEmpty(pendingId)) v.id = pendingId;
            String newKey = !TextUtils.isEmpty(pendingKey) ? pendingKey : v.sourceKey;
            if (TextUtils.isEmpty(newKey)) {
                nextOrGiveUp();
                return;
            }
            VodInfo nv = new VodInfo();
            nv.setVideo(v);
            nv.sourceKey = newKey;
            if (nv.seriesMap == null || nv.seriesMap.isEmpty()) {
                nextOrGiveUp();
                return;
            }
            // 定位到原源的同一集(播放组名相同则沿用, 否则取第一组)
            int oldPos = curInfo == null ? 0 : curInfo.getplayIndex();
            int oldGroupCount = curInfo == null ? 0 : curInfo.playGroupCount;
            String oldFlag = curInfo == null ? null : curInfo.playFlag;
            if (!TextUtils.isEmpty(oldFlag) && nv.seriesMap.containsKey(oldFlag)) {
                nv.playFlag = oldFlag;
            } else {
                nv.playFlag = (String) nv.seriesMap.keySet().toArray()[0];
            }
            List<VodInfo.VodSeries> series = nv.seriesMap.get(nv.playFlag);
            if (series == null || series.isEmpty()) {
                nextOrGiveUp();
                return;
            }
            int size = series.size();
            int groupCount = oldGroupCount > 0 ? oldGroupCount : size;
            if (groupCount > size) groupCount = size;
            if (groupCount <= 0) groupCount = 1;
            int pos = Math.max(0, Math.min(oldPos, size - 1));
            nv.playGroupCount = groupCount;
            nv.playGroup = pos / groupCount;
            nv.playIndex = pos % groupCount;
            nv.playerCfg = curInfo == null ? "" : curInfo.playerCfg;
            nv.reverseSort = false;

            searching = false;
            handler.removeCallbacks(timeoutTask);

            SourceBean sb = ApiConfig.get().getSource(nv.sourceKey);
            String sName = (sb != null && sb.getName() != null) ? sb.getName() : nv.sourceKey;
            curInfo = nv;
            curKey = nv.sourceKey;
            host.failoverSwitch(nv, sName);
        } catch (Throwable th) {
            th.printStackTrace();
            nextOrGiveUp();
        }
    }

    private void nextOrGiveUp() {
        if (searching) return; // 还在搜索, 等结果
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!tryNext()) host.failoverTip("当前线路播放失败，未找到其他可用线路", false, true);
            }
        });
    }
}
