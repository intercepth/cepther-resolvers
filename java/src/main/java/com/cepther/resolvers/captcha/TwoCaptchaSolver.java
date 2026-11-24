package com.cepther.resolvers.captcha;

import com.cepther.resolvers.http.Http;
import com.cepther.resolvers.http.HttpResponse;
import com.cepther.resolvers.util.MiniJson;

import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Client for the 2captcha-style HTTP API ({@code in.php} submit, {@code res.php} poll). Works with 2captcha.com
 * and services that copy its API (change {@code baseUrl}). The user supplies their own API key and pays the
 * service; nothing here is free or magic.
 *
 * <p>Unverified against the live service from this repo's CI (no network) -- run the CLI with a real key to check.
 */
public final class TwoCaptchaSolver implements CaptchaSolver {

    /** Injectable so tests don't actually wait. */
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final Http http;
    private final String apiKey;
    private final String baseUrl;
    private final long pollMillis;
    private final long timeoutMillis;
    private final Sleeper sleeper;

    public TwoCaptchaSolver(Http http, String apiKey) {
        this(http, apiKey, "https://2captcha.com", 5000, 180_000, Thread::sleep);
    }

    public TwoCaptchaSolver(Http http, String apiKey, String baseUrl, long pollMillis, long timeoutMillis, Sleeper sleeper) {
        this.http = http;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.pollMillis = pollMillis;
        this.timeoutMillis = timeoutMillis;
        this.sleeper = sleeper;
    }

    @Override
    public String solve(CaptchaChallenge c) throws CaptchaException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("key", apiKey);
        form.put("json", "1");
        switch (c.type()) {
            case IMAGE:
                if (c.imageBytes() == null) throw new CaptchaException("image challenge without image");
                form.put("method", "base64");
                form.put("body", Base64.getEncoder().encodeToString(c.imageBytes()));
                break;
            case RECAPTCHA_V2:
                form.put("method", "userrecaptcha");
                form.put("googlekey", c.siteKey());
                form.put("pageurl", c.pageUrl());
                break;
            case HCAPTCHA:
                form.put("method", "hcaptcha");
                form.put("sitekey", c.siteKey());
                form.put("pageurl", c.pageUrl());
                break;
            case TURNSTILE:
                form.put("method", "turnstile");
                form.put("sitekey", c.siteKey());
                form.put("pageurl", c.pageUrl());
                break;
            default:
                return null;
        }

        Map<String, Object> submit = json(post(baseUrl + "/in.php", form));
        if (MiniJson.longVal(submit.get("status"), 0) != 1) {
            throw new CaptchaException("submit rejected: " + submit.get("request"));
        }
        String id = String.valueOf(submit.get("request"));

        long waited = 0;
        while (waited <= timeoutMillis) {
            try {
                sleeper.sleep(pollMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CaptchaException("interrupted", e);
            }
            waited += pollMillis;
            String url = baseUrl + "/res.php?key=" + enc(apiKey) + "&action=get&json=1&id=" + enc(id);
            Map<String, Object> res = json(get(url));
            if (MiniJson.longVal(res.get("status"), 0) == 1) return String.valueOf(res.get("request"));
            String msg = String.valueOf(res.get("request"));
            if (!"CAPCHA_NOT_READY".equals(msg)) throw new CaptchaException("solver error: " + msg);
        }
        throw new CaptchaException("timed out after " + timeoutMillis + " ms");
    }

    private HttpResponse post(String url, Map<String, String> form) throws CaptchaException {
        try {
            return http.post(url, form, null);
        } catch (IOException e) {
            throw new CaptchaException("network: " + e.getMessage(), e);
        }
    }

    private HttpResponse get(String url) throws CaptchaException {
        try {
            return http.get(url, null);
        } catch (IOException e) {
            throw new CaptchaException("network: " + e.getMessage(), e);
        }
    }

    private static Map<String, Object> json(HttpResponse r) throws CaptchaException {
        try {
            Map<String, Object> m = MiniJson.map(MiniJson.parse(r.body()));
            if (m == null) throw new CaptchaException("unexpected response");
            return m;
        } catch (IllegalArgumentException e) {
            throw new CaptchaException("unexpected response: " + r.body(), e);
        }
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
