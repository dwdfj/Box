package com.github.tvbox.osc.dlna;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * 小贾影视仓 v23.6: UPnP AVTransport 的 SOAP 动作 —— 真正"把地址丢给电视播"的那一步。
 *
 * <p>流程与 AVBox 完全一致:
 * <pre>
 *   SetAVTransportURI(CurrentURI=视频地址, CurrentURIMetaData=DIDL-Lite)
 *        └─成功后─&gt; Play(InstanceID=0, Speed=1)
 *                       └─若带进度─&gt; Seek(Unit=REL_TIME)
 * </pre>
 * 全部方法阻塞, 必须在子线程调用。
 */
final class DlnaAvTransport {

    private static final String TAG = "DlnaAvTransport";

    /** AVTransport v1 服务类型。 */
    static final String AVT_SERVICE = "urn:schemas-upnp-org:service:AVTransport:1";

    private static final int CONNECT_TIMEOUT = 5000;
    private static final int READ_TIMEOUT = 8000;

    private DlnaAvTransport() {
    }

    /** 推地址 → 起播 → (可选)续播进度。任何一步失败都抛异常。 */
    static void cast(CastDevice device, CastVideo video) throws Exception {
        setAvTransportUri(device.controlUrl, video);
        play(device.controlUrl);
        if (video.position > 0) {
            try {
                seek(device.controlUrl, video.position);
            } catch (Throwable t) {
                // seek 失败不影响"已经投上去"这个结果, 与 AVBox 一样只记日志
                Log.i(TAG, "seek ignored: " + t.getMessage());
            }
        }
    }

    private static void setAvTransportUri(String controlUrl, CastVideo video) throws Exception {
        String body = "<u:SetAVTransportURI xmlns:u=\"" + AVT_SERVICE + "\">"
                + "<InstanceID>0</InstanceID>"
                + "<CurrentURI>" + escapeXml(video.url) + "</CurrentURI>"
                + "<CurrentURIMetaData>" + escapeXml(buildMetaData(video)) + "</CurrentURIMetaData>"
                + "</u:SetAVTransportURI>";
        soap(controlUrl, "SetAVTransportURI", body);
    }

    private static void play(String controlUrl) throws Exception {
        String body = "<u:Play xmlns:u=\"" + AVT_SERVICE + "\">"
                + "<InstanceID>0</InstanceID>"
                + "<Speed>1</Speed>"
                + "</u:Play>";
        soap(controlUrl, "Play", body);
    }

    private static void seek(String controlUrl, long positionMs) throws Exception {
        String body = "<u:Seek xmlns:u=\"" + AVT_SERVICE + "\">"
                + "<InstanceID>0</InstanceID>"
                + "<Unit>REL_TIME</Unit>"
                + "<Target>" + formatMs(positionMs) + "</Target>"
                + "</u:Seek>";
        soap(controlUrl, "Seek", body);
    }

    private static String soap(String controlUrl, String action, String innerBody) throws Exception {
        String envelope = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body>" + innerBody + "</s:Body>"
                + "</s:Envelope>";
        byte[] data = envelope.getBytes("UTF-8");

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(controlUrl).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            conn.setRequestProperty("SOAPACTION", "\"" + AVT_SERVICE + "#" + action + "\"");
            conn.setRequestProperty("Connection", "close");
            conn.setFixedLengthStreamingMode(data.length);

            OutputStream os = conn.getOutputStream();
            os.write(data);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            String resp = readAll((code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream());

            if (code < 200 || code >= 300) {
                throw new Exception("HTTP " + code + suffix(extractErrorCode(resp)));
            }
            if (resp != null && resp.contains(":Fault")) {
                throw new Exception("SOAP fault" + suffix(extractErrorCode(resp)));
            }
            Log.i(TAG, "soap ok: " + action);
            return resp;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 标准 DIDL-Lite 元数据(与 AVBox 的 buildMetaData 一致)。 */
    private static String buildMetaData(CastVideo video) {
        StringBuilder sb = new StringBuilder();
        sb.append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
                .append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
                .append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">")
                .append("<item id=\"0\" parentID=\"-1\" restricted=\"0\">")
                .append("<dc:title>").append(escapeXml(video.name)).append("</dc:title>")
                .append("<dc:creator></dc:creator>")
                .append("<upnp:class>object.item.videoItem</upnp:class>")
                .append("<res protocolInfo=\"http-get:*:video/*:*\">").append(escapeXml(video.url)).append("</res>")
                .append("</item>")
                .append("</DIDL-Lite>");
        return sb.toString();
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        } catch (Throwable ignored) {
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
        try {
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    private static String extractErrorCode(String soapResponse) {
        if (soapResponse == null) return null;
        int start = soapResponse.indexOf("<errorCode>");
        if (start < 0) return null;
        int end = soapResponse.indexOf("</errorCode>", start);
        if (end < 0) return null;
        return soapResponse.substring(start + "<errorCode>".length(), end).trim();
    }

    private static String suffix(String errorCode) {
        return (errorCode == null || errorCode.length() == 0) ? "" : " (" + errorCode + ")";
    }

    /** hh:mm:ss, Seek 的 REL_TIME 目标格式。 */
    private static String formatMs(long ms) {
        if (ms <= 0) return "00:00:00";
        long s = ms / 1000;
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }

    private static String escapeXml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
