package com.cepther.resolvers.sites;

import com.cepther.resolvers.ResolveContext;
import com.cepther.resolvers.ResolveException;
import com.cepther.resolvers.ResolveException.Reason;
import com.cepther.resolvers.ResolveResult;
import com.cepther.resolvers.ResolvedFile;
import com.cepther.resolvers.Resolver;
import com.cepther.resolvers.http.HttpResponse;
import com.cepther.resolvers.util.MiniJson;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pixeldrain files ({@code /u/ID}, {@code /api/file/ID}) and lists/albums ({@code /l/ID}) through its public
 * JSON API. No scraping, no captcha for normal use (the site may rate-limit heavy anonymous downloading).
 */
public final class PixeldrainResolver implements Resolver {
    private static final String BASE = "https://pixeldrain.com";
    private static final Pattern FILE = Pattern.compile("^/(?:u|api/file)/([A-Za-z0-9_-]+)");
    private static final Pattern LIST = Pattern.compile("^/(?:l|api/list)/([A-Za-z0-9_-]+)");

    @Override
    public String name() {
        return "pixeldrain";
    }

    @Override
    public boolean matches(URI url) {
        String h = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.ROOT);
        if (!(h.equals("pixeldrain.com") || h.equals("www.pixeldrain.com") || h.equals("pixeldrain.net"))) return false;
        String p = url.getPath() == null ? "" : url.getPath();
        return FILE.matcher(p).find() || LIST.matcher(p).find();
    }

    @Override
    public ResolveResult resolve(URI url, ResolveContext ctx) throws ResolveException {
        String p = url.getPath();
        Matcher fm = FILE.matcher(p);
        if (fm.find()) {
            String id = fm.group(1);
            Map<String, Object> info = api(ctx, BASE + "/api/file/" + id + "/info");
            return ResolveResult.of(file(id, info));
        }
        Matcher lm = LIST.matcher(p);
        if (lm.find()) {
            String id = lm.group(1);
            Map<String, Object> list = api(ctx, BASE + "/api/list/" + id);
            ResolveResult out = new ResolveResult().packageName(MiniJson.string(list.get("title")));
            List<Object> files = MiniJson.list(list.get("files"));
            if (files != null) {
                for (Object o : files) {
                    Map<String, Object> f = MiniJson.map(o);
                    String fid = f == null ? null : MiniJson.string(f.get("id"));
                    if (fid != null) out.add(file(fid, f));
                }
            }
            return out;
        }
        throw new ResolveException(Reason.UNSUPPORTED, "Unrecognised Pixeldrain URL");
    }

    private static ResolvedFile file(String id, Map<String, Object> info) {
        return ResolvedFile.direct(BASE + "/api/file/" + id + "?download")
                .name(MiniJson.string(info.get("name")))
                .size(MiniJson.longVal(info.get("size"), -1));
    }

    private static Map<String, Object> api(ResolveContext ctx, String url) throws ResolveException {
        HttpResponse r;
        try {
            r = ctx.http().get(url, null);
        } catch (IOException e) {
            throw new ResolveException(Reason.NETWORK, "Network error: " + e.getMessage(), e);
        }
        Map<String, Object> json;
        try {
            json = MiniJson.map(MiniJson.parse(r.body()));
        } catch (IllegalArgumentException e) {
            throw new ResolveException(Reason.PARSE_FAILED, "Pixeldrain returned non-JSON (HTTP " + r.status + ")", e);
        }
        if (json == null) throw new ResolveException(Reason.PARSE_FAILED, "Pixeldrain returned unexpected JSON");
        if (Boolean.FALSE.equals(json.get("success"))) {
            String value = String.valueOf(json.get("value"));
            String msg = String.valueOf(json.get("message"));
            if (value.contains("not_found")) throw new ResolveException(Reason.NOT_FOUND, "Pixeldrain: " + msg);
            throw new ResolveException(Reason.PARSE_FAILED, "Pixeldrain error " + value + ": " + msg);
        }
        return json;
    }
}
