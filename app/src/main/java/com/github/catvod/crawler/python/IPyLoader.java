package com.github.catvod.crawler.python;

import com.github.catvod.crawler.Spider;

import java.util.Map;

public interface IPyLoader {
    void clear();
    // 小贾影视仓 v26: 单个 py 源失效 —— 重新加载本地 .py/压缩包后旧 Spider 实例必须丢弃
    void invalidate(String key);
    void setConfig(String jsonStr);
    void setRecentPyKey(String pyApi);
    Spider getSpider(String key, String cls, String ext);
    Object[] proxyInvoke(Map<String, String> params);
}
