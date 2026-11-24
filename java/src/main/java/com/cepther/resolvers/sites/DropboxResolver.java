package com.cepther.resolvers.sites;

import com.cepther.resolvers.ResolveContext;
import com.cepther.resolvers.ResolveException;
import com.cepther.resolvers.ResolveException.Reason;
import com.cepther.resolvers.ResolveResult;
import com.cepther.resolvers.ResolvedFile;
import com.cepther.resolvers.Resolver;
import com.cepther.resolvers.http.HttpResponse;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Dropbox shared links (/s/, /scl/fi/, /scl/fo/, /sh/). The download address is the same link with
 * {@code dl=1}; shared folders come back as a zip. Password-protected links are reported as LOGIN_REQUIRED.
 */
public final class DropboxResolver implements Resolver {

    @Override
    public String name() {
        return "dropbox";
    }

    @Override
    public boolean matches(URI url) {
        String h = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.ROOT);
        boolean host = h.equals("dropbox.com") || h.endsWith(".dropbox.com") || h.equals("dl.dropboxusercontent.com");
        String p = url.getPath() == null ? "" : url.getPath();
        return host && (p.startsWith("/s/") || p.startsWith("/scl/") || p.startsWith("/sh/"));
    }

    @Override
    public ResolveResult resolve(URI url, ResolveContext ctx) throws ResolveException {
        String dl = withDl1(url);
        HttpResponse r;
        try {
            r = ctx.http().get(dl, null);
        } catch (IOException e) {
            throw new ResolveException(Reason.NETWORK, "Network error: " + e.getMessage(), e);
        }
        if (!r.isFile()) {
            String body = r.body().toLowerCase(Locale.ROOT);
            if (body.contains("password")) {
                throw new ResolveException(Reason.LOGIN_REQUIRED, "Dropbox link is password protected");
            }
            if (r.status == 404 || body.contains("doesn't exist") || body.contains("not found")) {
                throw new ResolveException(Reason.NOT_FOUND, "Dropbox link not found or removed");
            }
            throw new ResolveException(Reason.PARSE_FAILED, "Dropbox did not return a file (HTTP " + r.status + ")");
        }
        String name = r.fileName();
        if (name == null && !url.getPath().startsWith("/scl/fo/")) name = lastSegment(url.getPath());
        return ResolveResult.of(ResolvedFile.direct(dl).name(name).size(r.contentLength()));
    }

    /** Same link, {@code dl=0}/{@code raw=1} dropped and {@code dl=1} set; other params (rlkey, st) kept verbatim. */
    public static String withDl1(URI url) {
        String host = url.getHost().equalsIgnoreCase("dl.dropboxusercontent.com") ? url.getHost() : "www.dropbox.com";
        List<String> kept = new ArrayList<>();
        if (url.getRawQuery() != null) {
            for (String part : url.getRawQuery().split("&")) {
                String k = part.contains("=") ? part.substring(0, part.indexOf('=')) : part;
                if (k.equals("dl") || k.equals("raw") || part.isEmpty()) continue;
                kept.add(part);
            }
        }
        kept.add("dl=1");
        return "https://" + host + url.getRawPath() + "?" + String.join("&", kept);
    }

    private static String lastSegment(String path) {
        String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        String seg = p.substring(p.lastIndexOf('/') + 1);
        try {
            seg = URLDecoder.decode(seg.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            // keep raw
        }
        return seg.isEmpty() ? null : seg;
    }
}
