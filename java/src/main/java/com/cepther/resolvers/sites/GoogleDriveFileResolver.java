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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Google Drive single file ("anyone with the link"). Handles:
 * file/d/ID, open?id=, uc?id=, usercontent download?id=, and the large-file "can't scan for viruses" interstitial
 * (current form-based flow, plus the older confirm-token link).
 *
 * <p>Not covered: files that are not shared publicly, and Google-native docs (see {@link GoogleDocsResolver}).
 */
public final class GoogleDriveFileResolver implements Resolver {
    private static final Pattern OLD_CONFIRM_LINK = Pattern.compile("href=\"(/uc\\?export=download[^\"]*confirm=[^\"]+)\"");
    private static final int MAX_HOPS = 3;

    @Override
    public String name() {
        return "gdrive-file";
    }

    @Override
    public boolean matches(URI url) {
        return GoogleDriveUrls.folderId(url) == null && GoogleDriveUrls.fileId(url) != null;
    }

    @Override
    public ResolveResult resolve(URI url, ResolveContext ctx) throws ResolveException {
        String id = GoogleDriveUrls.fileId(url);
        String rk = GoogleDriveUrls.resourceKey(url);
        String next = "https://drive.google.com/uc?export=download&id=" + id + (rk != null ? "&resourcekey=" + rk : "");

        for (int hop = 0; hop <= MAX_HOPS; hop++) {
            HttpResponse r = get(ctx, next);
            if (r.isFile()) {
                ResolvedFile f = ResolvedFile.direct(next).name(r.fileName()).size(r.contentLength());
                f.header("Cookie", ctx.http().cookieHeader(next));
                return ResolveResult.of(f);
            }
            if (r.status == 404) throw new ResolveException(Reason.NOT_FOUND, "Drive file " + id + " not found");

            String html = r.body();
            Html.Form form = findDownloadForm(html, r.finalUrl);
            if (form != null) {
                next = form.submitUrl();
                continue;
            }
            Matcher old = OLD_CONFIRM_LINK.matcher(html);
            if (old.find()) {
                next = "https://drive.google.com" + Html.unescape(old.group(1));
                continue;
            }
            throw classify(r, id);
        }
        throw new ResolveException(Reason.PARSE_FAILED, "Drive kept returning confirmation pages for " + id);
    }

    private static Html.Form findDownloadForm(String html, String base) {
        for (Html.Form f : Html.forms(html, base)) {
            if ("download-form".equals(f.id) || f.fields.containsKey("confirm")) return f;
        }
        return null;
    }

    private static ResolveException classify(HttpResponse r, String id) {
        String body = r.body();
        String lower = body.toLowerCase();
        if (lower.contains("quota exceeded") || lower.contains("too many users have viewed or downloaded")
                || lower.contains("download quota")) {
            return new ResolveException(Reason.QUOTA_EXCEEDED,
                    "Drive download quota exceeded for " + id + " (try again later, or copy the file to your own Drive)");
        }
        String fin = r.finalUrl == null ? "" : r.finalUrl;
        if (fin.contains("accounts.google.com") || fin.contains("ServiceLogin")) {
            return new ResolveException(Reason.PRIVATE, "Drive file " + id + " is not shared with 'anyone with the link'");
        }
        if (lower.contains("file you have requested does not exist") || lower.contains("not found")) {
            return new ResolveException(Reason.NOT_FOUND, "Drive file " + id + " not found");
        }
        return new ResolveException(Reason.PARSE_FAILED,
                "Unrecognised Drive page for " + id + " (title: " + Html.title(body) + ") -- Google may have changed the flow");
    }

    private static HttpResponse get(ResolveContext ctx, String url) throws ResolveException {
        try {
            return ctx.http().get(url, null);
        } catch (IOException e) {
            throw new ResolveException(Reason.NETWORK, "Network error: " + e.getMessage(), e);
        }
    }
}
