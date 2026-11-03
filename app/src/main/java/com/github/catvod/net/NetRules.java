package com.github.catvod.net;

import com.orhanobut.hawk.Hawk;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 小贾影视仓 v26.4: 网络规则层(hosts 覆盖 + 广告域名拦截)。
 *
 * 背景: FongMi/TV 这类壳子在全局 OkHttp 上做了一层规则拦截, 能救回不少
 * "域名被污染 / 被墙 / 满屏广告"的源; 我们之前只有 DoH 一项。
 *
 * 规则来源(都放在 Hawk 里, 不内置任何可疑域名, 避免误伤):
 *   net_hosts  多行文本, 每行 "域名 IP"        —— 强制把某域名解析到固定 IP(带 Host 头)
 *   net_block  多行文本, 每行一个域名或片段    —— 命中即返回空 204, 不发真实请求(去广告/去统计)
 *
 * 默认只放了几条业界通用统计/广告域(可被 net_block 继续追加), 不影响正常源站。
 */
public final class NetRules {

    private static volatile Map<String, String> hosts;
    private static volatile List<String> blocks;

    private static final String KEY_HOSTS = "net_hosts";
    private static final String KEY_BLOCK = "net_block";

    private NetRules() {
    }

    private static synchronized void ensureLoaded() {
        if (hosts != null && blocks != null) return;
        Map<String, String> h = new HashMap<>();
        List<String> b = new ArrayList<>();
        // 通用广告/统计域(极少被正规源站使用)
        b.add("doubleclick.net");
        b.add("googlesyndication.com");
        b.add("googleadservices.com");
        b.add("umeng.com");
        b.add("cnzz.com");
        try {
            String customHosts = Hawk.get(KEY_HOSTS, "");
            if (customHosts != null) {
                for (String line : customHosts.split("\n")) {
                    String s = line.trim();
                    if (s.isEmpty() || s.startsWith("#")) continue;
                    String[] parts = s.split("\\s+");
                    if (parts.length >= 2) h.put(parts[0].toLowerCase(), parts[1]);
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            String customBlock = Hawk.get(KEY_BLOCK, "");
            if (customBlock != null) {
                for (String line : customBlock.split("\n")) {
                    String s = line.trim();
                    if (s.isEmpty() || s.startsWith("#")) continue;
                    b.add(s.toLowerCase());
                }
            }
        } catch (Throwable ignored) {
        }
        hosts = h;
        blocks = b;
    }

    /** 规则被修改后调用, 下次请求重新读取 */
    public static synchronized void reload() {
        hosts = null;
        blocks = null;
    }

    public static String mapHost(String host) {
        ensureLoaded();
        if (host == null) return null;
        return hosts.get(host.toLowerCase());
    }

    public static boolean isBlocked(String hostOrUrl) {
        ensureLoaded();
        if (hostOrUrl == null) return false;
        String s = hostOrUrl.toLowerCase();
        for (String b : blocks) {
            if (s.contains(b)) return true;
        }
        return false;
    }

    /** 挂到任意 OkHttpClient.Builder 上 */
    public static OkHttpClient.Builder install(OkHttpClient.Builder builder) {
        return builder.addInterceptor(new Interceptor() {
            @Override
            public Response intercept(Chain chain) throws IOException {
                Request request = chain.request();
                HttpUrl url = request.url();
                String host = url.host();
                if (isBlocked(host)) {
                    // 直接返回空响应, 请求不出网(去广告/去统计)
                    return new Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(204)
                            .message("Blocked by net rule")
                            .body(ResponseBody.create(MediaType.parse("text/plain"), new byte[0]))
                            .build();
                }
                String ip = mapHost(host);
                if (ip != null && !ip.equalsIgnoreCase(host)) {
                    // hosts 覆盖: 连到指定 IP, 但保留原 Host 头(部分源站靠 Host 分流)
                    HttpUrl replaced = url.newBuilder().host(ip).build();
                    request = request.newBuilder()
                            .url(replaced)
                            .header("Host", url.host() + (url.port() == HttpUrl.defaultPort(url.scheme()) ? "" : ":" + url.port()))
                            .build();
                }
                return chain.proceed(request);
            }
        });
    }
}
