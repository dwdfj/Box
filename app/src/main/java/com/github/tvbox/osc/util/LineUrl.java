package com.github.tvbox.osc.util;

/**
 * 小贾影视仓 v27.24：线路地址的「清理 + 迁移」规则，HomeActivity（面板/历史）与 App（启动迁移）共用一份，
 * 避免两处判断不一致导致「面板里看不到、或者能看见却打不开」。
 *
 * 结论来自 2026-10-31 的三轮实测（每轮都按引擎同一套规则解析出 sites 数，不只看 HTTP 200）。
 */
public class LineUrl {

    /** 实测确认打不开：域名 DNS 失效 / 服务端长时间无响应 / 配置结构已损坏 */
    public static boolean isDead(String url) {
        if (url == null || url.isEmpty()) return true;
        return url.contains("rihou.cc")                                    // 日后：DNS 解析失败
                || url.contains("摸鱼儿") || url.contains("xn--ihqu10cn4c")  // 摸鱼儿：21 秒无响应
                || url.contains("api.hgyx.vip");                            // 瓜子HGYX：配置被 // 注释坏
    }

    /** 把失效的旧地址换成现在能用的等价地址；不认识的地址原样返回 */
    public static String migrate(String url) {
        if (url == null) return null;
        String fixed = url;
        // GitHub 反代：v6.gh-proxy.org / ghfast.top 等实测 25~60 秒超时，445569.pages.dev 稳定 0.7~1.3 秒
        String[] deadProxy = {"https://v6.gh-proxy.org/", "https://ghfast.top/",
                "https://gh-proxy.org/", "https://gh-proxy.com/"};
        for (String d : deadProxy) {
            if (fixed.startsWith(d)) {
                fixed = "https://445569.pages.dev/" + fixed.substring(d.length());
                break;
            }
        }
        // monkeyray 的写法是 /raw.githubusercontent.com/...（不带内层 scheme），要补 https://
        String mr = "https://ghproxy.monkeyray.net/";
        if (fixed.startsWith(mr)) {
            fixed = "https://445569.pages.dev/https://" + fixed.substring(mr.length());
        }
        // 中文域名转 punycode：okhttp 的 HttpUrl 只接受 ASCII 主机名，
        // "http://饭太硬.net/tv" 在真机上会直接判非法 URL —— 这就是它明明能 ping 通却加载不出来的原因。
        fixed = fixed.replace("www.饭太硬.net", "www.xn--sss604efuw.net")
                .replace("www.饭太硬.art", "www.xn--sss604efuw.art")
                .replace("饭太硬.net", "xn--sss604efuw.net")
                .replace("饭太硬.art", "xn--sss604efuw.art")
                .replace("饭太硬.cc", "xn--sss604efuw.cc")
                .replace("肥猫.net", "xn--z7x900a.net");
        return fixed;
    }
}
