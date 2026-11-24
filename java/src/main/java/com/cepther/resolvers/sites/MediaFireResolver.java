package com.cepther.resolvers.sites;

import com.cepther.resolvers.ResolveContext;
import com.cepther.resolvers.ResolveException;
import com.cepther.resolvers.ResolveException.Reason;
import com.cepther.resolvers.ResolveResult;
import com.cepther.resolvers.ResolvedFile;
import com.cepther.resolvers.Resolver;
import com.cepther.resolvers.http.HttpResponse;
import com.cepther.resolvers.util.Html;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MediaFire single files. Reads the landing page and takes the download button's link (plain {@code href}
 * or the base64 {@code data-scrambled-url} variant), accepting only {@code downloadNNN.mediafire.com} hosts --
 * the same rule as the manifest entry, just with a smarter lookup. Folders are not handled.
 */
public final class MediaFireResolver implements Resolver {
    private static final Pattern DL_HOST = Pattern.compile("^download\\d*\\.mediafire\\.com$", Pattern.CASE_INSENSITIVE);
    private static final Pattern BUTTON = Pattern.compile(
            "<a\\b[^>]*(?:\\bid\\s*=\\s*[\"']downloadButton[\"']|aria-label\\s*=\\s*[\"']Download file[\"'])[^>]*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Override
    public String name() {
        return "mediafire";
    }

    @Override
    public boolean matches(URI url) {
        String h = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.ROOT);
        if (!(h.equals("mediafire.com") || h.equals("www.mediafire.com"))) return false;
        String p = url.getPath() == null ? "" : url.getPath();
        return p.startsWith("/file/") || p.startsWith("/download/") || p.startsWith("/view/")
                || ((p.isEmpty() || p.equals("/")) && url.getRawQuery() != null && !url.getRawQuery().isEmpty());
    }

    @Override
    public ResolveResult resolve(URI url, ResolveContext ctx) throws ResolveException {
        String page = url.toString();
        HttpResponse r;
        try {
            r = ctx.http().get(page, null);
        } catch (IOException e) {
            throw new ResolveException(Reason.NETWORK, "Network error: " + e.getMessage(), e);
        }
        if (r.isFile()) { // some links redirect straight to the file
            return ResolveResult.of(ResolvedFile.direct(r.finalUrl).name(r.fileName()).size(r.contentLength()));
        }
        String html = r.body();
        if (r.status == 404 || (r.finalUrl != null && r.finalUrl.contains("error.php"))
                || html.contains("Invalid or Deleted File")) {
            throw new ResolveException(Reason.NOT_FOUND, "MediaFire file not found or removed");
        }

        String link = fromButton(html, r.finalUrl);
        if (link == null) {
            for (String href : Html.hrefs(html, r.finalUrl)) {
                if (isDownloadHost(href)) { link = href; break; }
            }
        }
        if (link == null) {
            throw new ResolveException(Reason.PARSE_FAILED,
                    "No download link on the MediaFire page (title: " + Html.title(html) + ") -- page layout may have changed");
        }
        return ResolveResult.of(ResolvedFile.direct(link).name(nameFromUrl(link)));
    }

    private static String fromButton(String html, String base) {
        Matcher m = BUTTON.matcher(html);
        while (m.find()) {
            String tag = m.group();
            String scrambled = Html.attr(tag, "data-scrambled-url");
            if (scrambled != null && !scrambled.isEmpty()) {
                try {
                    String decoded = new String(Base64.getDecoder().decode(scrambled.trim()), StandardCharsets.UTF_8);
                    if (isDownloadHost(decoded)) return decoded;
                } catch (IllegalArgumentException ignored) {
                    // not base64; try href
                }
            }
            String href = Html.attr(tag, "href");
            if (href != null && !href.trim().isEmpty()) {
                String abs = Html.resolve(base, href.trim());
                if (abs != null && isDownloadHost(abs)) return abs;
            }
        }
        return null;
    }

    private static boolean isDownloadHost(String url) {
        try {
            String h = URI.create(url).getHost();
            return h != null && DL_HOST.matcher(h).matches();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String nameFromUrl(String url) {
        try {
            String p = URI.create(url).getRawPath();
            String seg = p.substring(p.lastIndexOf('/') + 1);
            return seg.isEmpty() ? null : URLDecoder.decode(seg.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
