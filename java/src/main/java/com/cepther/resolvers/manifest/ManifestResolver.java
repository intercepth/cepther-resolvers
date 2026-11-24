package com.cepther.resolvers.manifest;

import com.cepther.resolvers.ResolveContext;
import com.cepther.resolvers.ResolveException;
import com.cepther.resolvers.ResolveException.Reason;
import com.cepther.resolvers.ResolveResult;
import com.cepther.resolvers.ResolvedFile;
import com.cepther.resolvers.Resolver;
import com.cepther.resolvers.captcha.CaptchaChallenge;
import com.cepther.resolvers.http.HttpResponse;
import com.cepther.resolvers.util.Html;
import com.cepther.resolvers.util.MiniJson;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Runs the repo's data-only {@code manifest.json} entries ({@code direct_link}, {@code form_follow}) inside the
 * Java engine, so adding a simple site is still just a JSON edit. Unknown kinds and malformed entries are skipped,
 * exactly as the README promises.
 *
 * <p>Extra over the plain data semantics: if the confirm form being submitted contains a reCAPTCHA / hCaptcha /
 * Turnstile widget, the context's captcha solver is asked for a token and the matching response field is added.
 */
public final class ManifestResolver implements Resolver {
    private static final Pattern SITEKEY = Pattern.compile("<[^>]*data-sitekey\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final String host;
    private final String kind;
    private final Pattern linkHost;     // direct_link
    private final Pattern idPattern;    // form_follow
    private final String firstUrlTemplate;
    private final String anyField;
    private final String actionContains;

    private ManifestResolver(String host, String kind, Pattern linkHost, Pattern idPattern,
                             String firstUrlTemplate, String anyField, String actionContains) {
        this.host = host;
        this.kind = kind;
        this.linkHost = linkHost;
        this.idPattern = idPattern;
        this.firstUrlTemplate = firstUrlTemplate;
        this.anyField = anyField;
        this.actionContains = actionContains;
    }

    /** Parse a manifest body. Wrong schema_version / shape -> empty list; bad single entries are skipped. */
    public static List<Resolver> fromJson(String json) {
        List<Resolver> out = new ArrayList<>();
        Map<String, Object> root;
        try {
            root = MiniJson.map(MiniJson.parse(json));
        } catch (IllegalArgumentException e) {
            return out;
        }
        if (root == null || MiniJson.longVal(root.get("schema_version"), -1) != 1) return out;
        List<Object> hosts = MiniJson.list(root.get("hosts"));
        if (hosts == null) return out;
        for (Object o : hosts) {
            Resolver r = parseEntry(MiniJson.map(o));
            if (r != null) out.add(r);
        }
        return out;
    }

    private static Resolver parseEntry(Map<String, Object> e) {
        if (e == null) return null;
        String host = MiniJson.string(e.get("host"));
        String kind = MiniJson.string(e.get("kind"));
        if (host == null || host.isEmpty() || kind == null) return null;
        try {
            if (kind.equals("direct_link")) {
                String p = MiniJson.string(e.get("link_host_pattern"));
                if (p == null) return null;
                return new ManifestResolver(host.toLowerCase(Locale.ROOT), kind, Pattern.compile(p), null, null, null, null);
            }
            if (kind.equals("form_follow")) {
                String idp = MiniJson.string(e.get("id_pattern"));
                String tpl = MiniJson.string(e.get("first_url_template"));
                Map<String, Object> fm = MiniJson.map(e.get("form_match"));
                if (idp == null || tpl == null || !tpl.contains("{id}") || fm == null) return null;
                String any = MiniJson.string(fm.get("any_field"));
                String act = MiniJson.string(fm.get("action_contains"));
                if (any == null && act == null) return null;
                return new ManifestResolver(host.toLowerCase(Locale.ROOT), kind, null, Pattern.compile(idp), tpl, any, act);
            }
        } catch (PatternSyntaxException ex) {
            return null;
        }
        return null; // unknown kind: skipped, not fatal
    }

    @Override
    public String name() {
        return "manifest:" + host + ":" + kind;
    }

    @Override
    public boolean matches(URI url) {
        String h = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.ROOT);
        if (h.startsWith("www.")) h = h.substring(4);
        if (!(h.equals(host) || h.endsWith("." + host))) return false;
        if (kind.equals("form_follow")) return idPattern.matcher(url.toString()).find();
        return true;
    }

    @Override
    public ResolveResult resolve(URI url, ResolveContext ctx) throws ResolveException {
        return kind.equals("direct_link") ? directLink(url, ctx) : formFollow(url, ctx);
    }

    private ResolveResult directLink(URI url, ResolveContext ctx) throws ResolveException {
        HttpResponse r = get(ctx, url.toString());
        if (r.isFile()) return ResolveResult.of(fileOf(r.finalUrl, r));
        for (String href : Html.hrefs(r.body(), r.finalUrl)) {
            try {
                String h = URI.create(href).getHost();
                if (h != null && linkHost.matcher(h).find()) return ResolveResult.of(ResolvedFile.direct(href).name(nameOf(href)));
            } catch (IllegalArgumentException ignored) {
                // skip bad href
            }
        }
        throw new ResolveException(Reason.PARSE_FAILED, "No link on " + url.getHost() + " matched " + linkHost.pattern());
    }

    private ResolveResult formFollow(URI url, ResolveContext ctx) throws ResolveException {
        Matcher m = idPattern.matcher(url.toString());
        if (!m.find() || m.groupCount() < 1) throw new ResolveException(Reason.UNSUPPORTED, "No file id in URL");
        String first = firstUrlTemplate.replace("{id}", m.group(1));
        HttpResponse r = get(ctx, first);
        if (r.isFile()) return ResolveResult.of(fileOf(first, r));

        Html.Form chosen = null;
        for (Html.Form f : Html.forms(r.body(), r.finalUrl)) {
            boolean ok = true;
            if (anyField != null && !f.fields.containsKey(anyField)) ok = false;
            if (actionContains != null && !f.action.contains(actionContains)) ok = false;
            if (ok) { chosen = f; break; }
        }
        if (chosen == null) {
            throw new ResolveException(Reason.PARSE_FAILED, "No matching confirm form on " + url.getHost());
        }
        attachCaptcha(chosen, r.finalUrl, ctx);
        String submit = chosen.submitUrl();
        HttpResponse r2 = get(ctx, submit);
        String result = r2.finalUrl == null ? submit : r2.finalUrl;
        return ResolveResult.of(r2.isFile() ? fileOf(result, r2) : ResolvedFile.direct(result).name(nameOf(result)));
    }

    private static void attachCaptcha(Html.Form form, String pageUrl, ResolveContext ctx) throws ResolveException {
        Matcher m = SITEKEY.matcher(form.innerHtml);
        if (!m.find()) return;
        String tag = m.group().toLowerCase(Locale.ROOT);
        CaptchaChallenge.Type type = tag.contains("h-captcha") ? CaptchaChallenge.Type.HCAPTCHA
                : tag.contains("cf-turnstile") ? CaptchaChallenge.Type.TURNSTILE
                : CaptchaChallenge.Type.RECAPTCHA_V2;
        String token = ctx.solveCaptcha(CaptchaChallenge.widget(type, pageUrl, m.group(1)));
        form.fields.put(type.responseField(), token);
    }

    private static HttpResponse get(ResolveContext ctx, String url) throws ResolveException {
        try {
            return ctx.http().get(url, null);
        } catch (IOException e) {
            throw new ResolveException(Reason.NETWORK, "Network error: " + e.getMessage(), e);
        }
    }

    private static ResolvedFile fileOf(String url, HttpResponse r) {
        String name = r.fileName();
        return ResolvedFile.direct(url).name(name != null ? name : nameOf(url)).size(r.contentLength());
    }

    private static String nameOf(String url) {
        try {
            String p = URI.create(url).getRawPath();
            String seg = p == null ? "" : p.substring(p.lastIndexOf('/') + 1);
            return seg.isEmpty() ? null : URLDecoder.decode(seg.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
