package com.cepther.resolvers.http;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HttpResponse {
    private static final Pattern CD_STAR = Pattern.compile("filename\\*\\s*=\\s*[^']*'[^']*'([^;]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CD_PLAIN = Pattern.compile("filename\\s*=\\s*\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE);

    public final int status;
    public final String requestUrl;
    /** URL after redirects. */
    public final String finalUrl;
    /** Lower-cased header names -> first value. */
    public final Map<String, String> headers;
    /** Text body for html/json/xml responses; {@code null} when the response is (probably) a file. */
    public final String body;

    public HttpResponse(int status, String requestUrl, String finalUrl, Map<String, String> headers, String body) {
        this.status = status;
        this.requestUrl = requestUrl;
        this.finalUrl = finalUrl == null ? requestUrl : finalUrl;
        Map<String, String> h = new HashMap<>();
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                h.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
            }
        }
        this.headers = Collections.unmodifiableMap(h);
        this.body = body;
    }

    public String header(String name) {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }

    public String contentType() {
        String ct = header("content-type");
        return ct == null ? "" : ct.toLowerCase(Locale.ROOT);
    }

    public boolean isHtml() {
        String ct = contentType();
        return ct.contains("html");
    }

    /** True when this response is the file itself: success status and no textual body was read. */
    public boolean isFile() {
        return status >= 200 && status < 300 && body == null;
    }

    public String body() {
        return body == null ? "" : body;
    }

    /** File name from Content-Disposition, or null. */
    public String fileName() {
        String cd = header("content-disposition");
        if (cd == null) return null;
        Matcher m = CD_STAR.matcher(cd);
        try {
            if (m.find()) return URLDecoder.decode(m.group(1).trim().replace("+", "%2B"), "UTF-8");
            m = CD_PLAIN.matcher(cd);
            if (m.find()) return m.group(1).trim();
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    /** Content-Length in bytes, or -1. */
    public long contentLength() {
        String v = header("content-length");
        if (v == null) return -1;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
