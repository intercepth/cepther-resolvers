package com.cepther.resolvers.captcha;

/** A captcha a resolver ran into. Immutable. */
public final class CaptchaChallenge {

    public enum Type {
        /** Distorted-text image; answer is the typed text. */
        IMAGE(null),
        RECAPTCHA_V2("g-recaptcha-response"),
        HCAPTCHA("h-captcha-response"),
        TURNSTILE("cf-turnstile-response");

        private final String responseField;

        Type(String responseField) {
            this.responseField = responseField;
        }

        /** Form field the site expects the token in, or null for IMAGE (site-specific field). */
        public String responseField() {
            return responseField;
        }
    }

    private final Type type;
    private final String pageUrl;
    private final String siteKey;
    private final byte[] image;

    private CaptchaChallenge(Type type, String pageUrl, String siteKey, byte[] image) {
        this.type = type;
        this.pageUrl = pageUrl;
        this.siteKey = siteKey;
        this.image = image;
    }

    public static CaptchaChallenge widget(Type type, String pageUrl, String siteKey) {
        if (type == Type.IMAGE) throw new IllegalArgumentException("use image()");
        return new CaptchaChallenge(type, pageUrl, siteKey, null);
    }

    public static CaptchaChallenge image(String pageUrl, byte[] imageBytes) {
        return new CaptchaChallenge(Type.IMAGE, pageUrl, null, imageBytes);
    }

    public Type type() { return type; }
    /** Page the captcha was shown on (solving services need it for token captchas). */
    public String pageUrl() { return pageUrl; }
    public String siteKey() { return siteKey; }
    public byte[] imageBytes() { return image; }
}
