package com.cepther.resolvers;

import com.cepther.resolvers.captcha.CaptchaChallenge;
import com.cepther.resolvers.captcha.CaptchaException;
import com.cepther.resolvers.captcha.CaptchaSolver;
import com.cepther.resolvers.http.Http;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Everything a resolver may use. Built once by the app and passed to every call. */
public final class ResolveContext {
    private final Http http;
    private CaptchaSolver captcha = CaptchaSolver.NONE;
    private Consumer<String> log = s -> { };
    private final Map<String, String> options = new HashMap<>();

    public ResolveContext(Http http) {
        this.http = http;
    }

    public ResolveContext captcha(CaptchaSolver s) {
        this.captcha = s == null ? CaptchaSolver.NONE : s;
        return this;
    }

    public ResolveContext log(Consumer<String> l) {
        this.log = l == null ? s -> { } : l;
        return this;
    }

    /**
     * Known options: {@code gdrive.apiKey} (Drive API key, makes folder listing complete),
     * {@code gdrive.maxDepth} (subfolder depth, default 10),
     * {@code gdocs.format} (export format for native Google Docs/Sheets/Slides, e.g. pdf).
     */
    public ResolveContext option(String key, String value) {
        if (value == null) options.remove(key);
        else options.put(key, value);
        return this;
    }

    public Http http() { return http; }

    public String option(String key) { return options.get(key); }

    public int intOption(String key, int dflt) {
        try {
            String v = options.get(key);
            return v == null ? dflt : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    public void log(String msg) { log.accept(msg); }

    /**
     * Ask the configured solver. Throws {@link ResolveException.Reason#CAPTCHA_REQUIRED} when there is
     * no solver or it cannot answer, so the app can fall back to showing the captcha to the user.
     */
    public String solveCaptcha(CaptchaChallenge c) throws ResolveException {
        String answer;
        try {
            answer = captcha.solve(c);
        } catch (CaptchaException e) {
            throw new ResolveException(ResolveException.Reason.CAPTCHA_REQUIRED,
                    "Captcha (" + c.type() + ") failed: " + e.getMessage(), e);
        }
        if (answer == null || answer.isEmpty()) {
            throw new ResolveException(ResolveException.Reason.CAPTCHA_REQUIRED,
                    "Captcha (" + c.type() + ") on " + c.pageUrl() + " needs solving and no solver answered");
        }
        return answer;
    }
}
