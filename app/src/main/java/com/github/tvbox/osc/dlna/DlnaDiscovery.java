package com.github.tvbox.osc.dlna;

import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 小贾影视仓 v23.6: 极简 DLNA/UPnP 发现层 —— 不引第三方库(不依赖 cling)。
 *
 * <p>只做两件事:
 * <ol>
 *   <li>SSDP M-SEARCH 组播, 找出网内所有 {@code MediaRenderer};</li>
 *   <li>拉取每台设备的描述 XML, 解析出它的 {@code AVTransport} controlURL。</li>
 * </ol>
 * 行为与 AVBox 的 {@code org.fourthline.cling} 用法一致(它也是搜 MediaRenderer + 找 AVTransport),
 * 只是把整条 UPnP 协议栈裁到"够用"为止, 从而避免给 release 混淆(minifyEnabled)引入反辐射风险。
 *
 * <p>本类所有方法都是阻塞的, 必须在子线程调用。
 */
final class DlnaDiscovery {

    private static final String TAG = "DlnaDiscovery";

    private static final String SSDP_HOST = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final String ST_MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1";

    private static final int DESC_CONNECT_TIMEOUT = 4000;
    private static final int DESC_READ_TIMEOUT = 6000;

    interface DeviceFound {
        void onFound(CastDevice device);
    }

    private DlnaDiscovery() {
    }

    /**
     * 发 M-SEARCH 并在 {@code timeoutMs} 内收集设备。
     *
     * @param cb 每发现一台回调一次(仍在调用线程上, 不做线程切换)
     * @return 本次收集到的全部设备
     */
    static List<CastDevice> search(long timeoutMs, DeviceFound cb) {
        final List<CastDevice> result = new ArrayList<CastDevice>();
        final Set<String> seenLocation = new HashSet<String>();
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(600);

            byte[] payload = buildSearchMessage().getBytes("UTF-8");
            InetAddress target = InetAddress.getByName(SSDP_HOST);
            // 有些设备的 SSDP 栈会丢掉第一个包, 连发三次(与绝大多数 UPnP control point 一致)
            for (int i = 0; i < 3; i++) {
                socket.send(new DatagramPacket(payload, payload.length, target, SSDP_PORT));
            }

            long deadline = System.currentTimeMillis() + timeoutMs;
            byte[] buf = new byte[4096];
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket in = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(in);
                } catch (java.net.SocketTimeoutException e) {
                    continue; // 本轮没收到, 继续等到 deadline
                } catch (Throwable t) {
                    break;
                }
                String resp = new String(in.getData(), 0, in.getLength(), "UTF-8");
                String location = headerValue(resp, "LOCATION");
                if (location == null || location.length() == 0 || !seenLocation.add(location)) continue;
                CastDevice dev = parseDescription(location);
                if (dev != null) {
                    result.add(dev);
                    if (cb != null) cb.onFound(dev);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "ssdp search failed: " + t.getMessage());
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return result;
    }

    private static String buildSearchMessage() {
        return "M-SEARCH * HTTP/1.1\r\n"
                + "HOST: " + SSDP_HOST + ":" + SSDP_PORT + "\r\n"
                + "MAN: \"ssdp:discover\"\r\n"
                + "MX: 2\r\n"
                + "ST: " + ST_MEDIA_RENDERER + "\r\n"
                + "USER-AGENT: Android/1.0 UPnP/1.0 TVBox/1.0\r\n"
                + "\r\n";
    }

    /** 取 SSDP 响应头(大小写不敏感), 找不到返回 null。 */
    private static String headerValue(String response, String name) {
        if (response == null) return null;
        String[] lines = response.split("\r\n");
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            if (line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    /**
     * 拉设备描述 XML 并解析。
     * 只认"描述了 AVTransport 服务"的设备 —— 那正是能接收视频地址的那一类。
     *
     * @return 解析成功返回 CastDevice, 否则 null
     */
    static CastDevice parseDescription(String location) {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            URL url = new URL(location);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(DESC_CONNECT_TIMEOUT);
            conn.setReadTimeout(DESC_READ_TIMEOUT);
            conn.setRequestProperty("Connection", "close");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            in = conn.getInputStream();

            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(in, "UTF-8");

            String friendlyName = null;
            String udn = null;
            String controlUrl = null;
            String currentServiceType = null;
            String text = null;

            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if ("serviceType".equalsIgnoreCase(tag)) currentServiceType = null;
                    text = null;
                } else if (event == XmlPullParser.TEXT) {
                    text = parser.getText();
                } else if (event == XmlPullParser.END_TAG) {
                    String tag = parser.getName();
                    if ("friendlyName".equalsIgnoreCase(tag)) {
                        if (friendlyName == null) friendlyName = trim(text);
                    } else if ("UDN".equalsIgnoreCase(tag)) {
                        if (udn == null) udn = trim(text);
                    } else if ("serviceType".equalsIgnoreCase(tag)) {
                        currentServiceType = trim(text);
                    } else if ("controlURL".equalsIgnoreCase(tag)) {
                        // 只取 AVTransport 服务的 controlURL
                        if (controlUrl == null
                                && currentServiceType != null
                                && currentServiceType.toLowerCase(Locale.US).contains("avtransport")) {
                            controlUrl = trim(text);
                        }
                    }
                    text = null;
                }
                event = parser.next();
            }

            if (controlUrl == null || controlUrl.length() == 0) return null;

            CastDevice device = new CastDevice(
                    (udn != null && udn.length() > 0) ? udn : location,
                    friendlyName,
                    location);
            device.controlUrl = resolve(location, controlUrl);
            return device;
        } catch (Throwable t) {
            Log.d(TAG, "parse description failed: " + location + " / " + t.getMessage());
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
            if (conn != null) conn.disconnect();
        }
    }

    /** 把描述 XML 里的相对 controlURL 补成绝对地址。 */
    private static String resolve(String location, String path) {
        try {
            if (path.startsWith("http://") || path.startsWith("https://")) return path;
            URL base = new URL(location);
            String origin = base.getProtocol() + "://" + base.getHost()
                    + (base.getPort() > 0 ? ":" + base.getPort() : "");
            if (path.startsWith("/")) return origin + path;
            String dir = base.getPath();
            int slash = dir == null ? -1 : dir.lastIndexOf('/');
            dir = (slash >= 0) ? dir.substring(0, slash + 1) : "/";
            return origin + dir + path;
        } catch (Throwable t) {
            return path;
        }
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
