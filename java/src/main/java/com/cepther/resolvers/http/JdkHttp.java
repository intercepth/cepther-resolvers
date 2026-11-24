package com.cepther.resolvers.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

/** Reference {@link Http} on java.net.http (JDK 11+). Not usable on Android -- swap in OkHttp there. */
public final class JdkHttp implements Http {
    public static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final int MAX_TEXT_BYTES = 4 * 1024 * 1024;

    private final CookieManager cookies = new CookieManager();
    private final HttpClient client;

    public JdkHttp() {
        cookies.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        client = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    @Override
    public HttpResponse get(String url, Map<String, String> headers) throws IOException {
        return send(HttpRequest.newBuilder(URI.create(url)).GET(), url, headers);
    }

    @Override
    public HttpResponse post(String url, Map<String, String> form, Map<String, String> headers) throws IOException {
        StringJoiner body = new StringJoiner("&");
        if (form != null) {
            for (Map.Entry<String, String> e : form.entrySet()) {
                body.add(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            }
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        return send(b, url, headers);
    }

    @Override
    public String cookieHeader(String url) {
        try {
            List<HttpCookie> list = cookies.getCookieStore().get(URI.create(url));
            if (list.isEmpty()) return null;
            StringJoiner j = new StringJoiner("; ");
            for (HttpCookie c : list) j.add(c.getName() + "=" + c.getValue());
            return j.toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private HttpResponse send(HttpRequest.Builder b, String url, Map<String, String> headers) throws IOException {
        b.timeout(Duration.ofSeconds(30))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9");
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) b.header(e.getKey(), e.getValue());
        }
        java.net.http.HttpResponse<InputStream> r;
        try {
            r = client.send(b.build(), java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        Map<String, String> h = new HashMap<>();
        r.headers().map().forEach((k, v) -> {
            if (!v.isEmpty()) h.put(k.toLowerCase(Locale.ROOT), v.get(0));
        });
        String ct = h.getOrDefault("content-type", "").toLowerCase(Locale.ROOT);
        String cd = h.getOrDefault("content-disposition", "").toLowerCase(Locale.ROOT);
        boolean textual = !cd.contains("attachment")
                && (ct.contains("html") || ct.contains("json") || ct.contains("xml"));
        String body = null;
        try (InputStream in = r.body()) {
            if (textual) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0 && bos.size() < MAX_TEXT_BYTES) bos.write(buf, 0, n);
                body = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            }
            // non-textual: closing the stream aborts the transfer; we only wanted the headers
        }
        return new HttpResponse(r.statusCode(), url, r.uri().toString(), h, body);
    }
}
