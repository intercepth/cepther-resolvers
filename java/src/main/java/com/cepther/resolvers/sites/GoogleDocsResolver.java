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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Native Google Docs / Sheets / Slides -> export download. Default formats: docx, xlsx, pptx;
 * override with option {@code gdocs.format} (e.g. "pdf"). Only works for documents shared "anyone with the link".
 */
public final class GoogleDocsResolver implements Resolver {
    private static final Pattern DOC = Pattern.compile("^/(document|spreadsheets|presentation)/d/([A-Za-z0-9_-]{10,})");

    @Override
    public String name() {
        return "gdocs";
    }

    @Override
    public boolean matches(URI url) {
        return "docs.google.com".equalsIgnoreCase(url.getHost())
                && url.getPath() != null && DOC.matcher(url.getPath()).find();
    }

    @Override
    public ResolveResult resolve(URI url, ResolveContext ctx) throws ResolveException {
        Matcher m = DOC.matcher(url.getPath());
        if (!m.find()) throw new ResolveException(Reason.UNSUPPORTED, "Not a Docs/Sheets/Slides URL");
        String kind = m.group(1);
        String id = m.group(2);
        String fmt = ctx.option("gdocs.format");
        if (fmt == null || fmt.trim().isEmpty()) fmt = defaultFormat(kind);
        fmt = fmt.trim().toLowerCase();

        String exportUrl = "https://docs.google.com/" + kind + "/d/" + id
                + (kind.equals("presentation") ? "/export/" + fmt : "/export?format=" + fmt);

        HttpResponse r;
        try {
            r = ctx.http().get(exportUrl, null);
        } catch (IOException e) {
            throw new ResolveException(Reason.NETWORK, "Network error: " + e.getMessage(), e);
        }
        if (r.isFile()) {
            return ResolveResult.of(ResolvedFile.direct(exportUrl).name(r.fileName()).size(r.contentLength())
                    .header("Cookie", ctx.http().cookieHeader(exportUrl)));
        }
        if (r.status == 404) throw new ResolveException(Reason.NOT_FOUND, "Document " + id + " not found");
        String fin = r.finalUrl == null ? "" : r.finalUrl;
        if (fin.contains("accounts.google.com") || r.status == 401 || r.status == 403) {
            throw new ResolveException(Reason.PRIVATE, "Document " + id + " is not shared with 'anyone with the link'");
        }
        throw new ResolveException(Reason.PARSE_FAILED, "Export of " + id + " as '" + fmt + "' did not return a file");
    }

    private static String defaultFormat(String kind) {
        switch (kind) {
            case "spreadsheets": return "xlsx";
            case "presentation": return "pptx";
            default: return "docx";
        }
    }

    /** Page URL for a Google-native mime type (used by the folder lister), or null if not exportable. */
    static String editUrlFor(String mime, String id) {
        switch (mime) {
            case "application/vnd.google-apps.document": return "https://docs.google.com/document/d/" + id + "/edit";
            case "application/vnd.google-apps.spreadsheet": return "https://docs.google.com/spreadsheets/d/" + id + "/edit";
            case "application/vnd.google-apps.presentation": return "https://docs.google.com/presentation/d/" + id + "/edit";
            default: return null;
        }
    }
}
