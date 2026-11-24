package com.cepther.resolvers.sites;

import com.cepther.resolvers.ResolveContext;
import com.cepther.resolvers.ResolveException;
import com.cepther.resolvers.ResolveException.Reason;
import com.cepther.resolvers.ResolveResult;
import com.cepther.resolvers.ResolvedFile;
import com.cepther.resolvers.Resolver;
import com.cepther.resolvers.http.HttpResponse;
import com.cepther.resolvers.util.Html;
import com.cepther.resolvers.util.MiniJson;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Google Drive public folder -> list of file links (recursing into subfolders).
 *
 * <ul>
 *   <li><b>API mode</b> (option {@code gdrive.apiKey}): Drive v3 {@code files.list}, paginated, complete, with
 *       sizes. Recommended: free API key from Google Cloud Console with the Drive API enabled.</li>
 *   <li><b>Scrape mode</b> (no key): parses {@code embeddedfolderview}. No key needed but Google only renders
 *       roughly the first 50 entries per folder, and the HTML is not a stable API -- a warning is added when
 *       the listing looks truncated. Unverified against the live site from this repo (no network in CI).</li>
 * </ul>
 *
 * <p>Returned files are non-direct links ({@code drive.google.com/file/d/ID/view}); the registry resolves each.
 */
public final class GoogleDriveFolderResolver implements Resolver {
    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";
    private static final String API = "https://www.googleapis.com/drive/v3/files";
    private static final Pattern ENTRY = Pattern.compile("id=\"entry-([A-Za-z0-9_-]+)\"");
    private static final Pattern HREF = Pattern.compile("href=\"([^\"]+)\"");
    private static final Pattern ENTRY_TITLE = Pattern.compile("class=\"flip-entry-title\"[^>]*>([^<]*)<");
    private static final int SCRAPE_TRUNCATION_HINT = 50;

    @Override
    public String name() {
        return "gdrive-folder";
    }

    @Override
    public boolean matches(URI url) {
        return GoogleDriveUrls.folderId(url) != null;
    }

    @Override
    public ResolveResult resolve(URI url, ResolveContext ctx) throws ResolveException {
        String root = GoogleDriveUrls.folderId(url);
        int maxDepth = ctx.intOption("gdrive.maxDepth", 10);
        ResolveResult out = new ResolveResult();
        String key = ctx.option("gdrive.apiKey");
        if (key != null && !key.trim().isEmpty()) {
            out.packageName(apiFolderName(ctx, root, key.trim()));
            listApi(ctx, root, key.trim(), "", maxDepth, out);
        } else {
            out.warn("No gdrive.apiKey set: using the embedded folder view, which may omit entries beyond ~50 per folder.");
            listScrape(ctx, root, "", maxDepth, out, true);
        }
        return out;
    }

    // ---- API mode -------------------------------------------------------------------------------------------

    private void listApi(ResolveContext ctx, String folderId, String key, String path, int depth, ResolveResult out)
            throws ResolveException {
        String pageToken = null;
        do {
            String url = API + "?q=" + enc("'" + folderId + "' in parents and trashed=false")
                    + "&fields=" + enc("nextPageToken,files(id,name,mimeType,size)")
                    + "&pageSize=1000&supportsAllDrives=true&includeItemsFromAllDrives=true&key=" + enc(key)
                    + (pageToken != null ? "&pageToken=" + enc(pageToken) : "");
            Map<String, Object> json = apiJson(ctx, url, folderId);
            List<Object> files = MiniJson.list(json.get("files"));
            if (files != null) {
                for (Object o : files) {
                    Map<String, Object> f = MiniJson.map(o);
                    if (f == null) continue;
                    String id = MiniJson.string(f.get("id"));
                    String name = MiniJson.string(f.get("name"));
                    String mime = MiniJson.string(f.get("mimeType"));
                    if (id == null || name == null) continue;
                    if (FOLDER_MIME.equals(mime)) {
                        if (depth > 0) {
                            listApi(ctx, id, key, join(path, name), depth - 1, out);
                        } else {
                            out.warn("Subfolder '" + name + "' skipped: gdrive.maxDepth reached");
                        }
                    } else if (mime != null && mime.startsWith("application/vnd.google-apps.")) {
                        String docUrl = GoogleDocsResolver.editUrlFor(mime, id);
                        if (docUrl != null) {
                            out.add(ResolvedFile.link(docUrl).name(name).path(path));
                        } else {
                            out.warn("Skipped '" + name + "' (" + mime + " cannot be downloaded)");
                        }
                    } else {
                        out.add(ResolvedFile.link(GoogleDriveUrls.fileViewUrl(id))
                                .name(name).size(MiniJson.longVal(f.get("size"), -1)).path(path));
                    }
                }
            }
            pageToken = MiniJson.string(json.get("nextPageToken"));
        } while (pageToken != null && !pageToken.isEmpty());
    }

    private String apiFolderName(ResolveContext ctx, String folderId, String key) {
        try {
            Map<String, Object> json = apiJson(ctx, API + "/" + folderId + "?fields=name&supportsAllDrives=true&key=" + enc(key), folderId);
            return MiniJson.string(json.get("name"));
        } catch (ResolveException e) {
            return null; // name is cosmetic; the listing call will raise the real error
        }
    }

    private Map<String, Object> apiJson(ResolveContext ctx, String url, String folderId) throws ResolveException {
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
            throw new ResolveException(Reason.PARSE_FAILED, "Drive API returned non-JSON (HTTP " + r.status + ")", e);
        }
        if (json == null) throw new ResolveException(Reason.PARSE_FAILED, "Drive API returned unexpected JSON");
        Map<String, Object> err = MiniJson.map(json.get("error"));
        if (err != null) {
            long code = MiniJson.longVal(err.get("code"), r.status);
            String msg = String.valueOf(err.get("message"));
            if (code == 404) throw new ResolveException(Reason.NOT_FOUND, "Folder " + folderId + " not found");
            if (code == 401 || code == 403) {
                throw new ResolveException(Reason.PRIVATE, "Drive API refused access to " + folderId + ": " + msg);
            }
            throw new ResolveException(Reason.PARSE_FAILED, "Drive API error " + code + ": " + msg);
        }
        return json;
    }

    // ---- Scrape mode ----------------------------------------------------------------------------------------

    private void listScrape(ResolveContext ctx, String folderId, String path, int depth, ResolveResult out, boolean top)
            throws ResolveException {
        String url = "https://drive.google.com/embeddedfolderview?id=" + folderId;
        HttpResponse r;
        try {
            r = ctx.http().get(url, null);
        } catch (IOException e) {
            throw new ResolveException(Reason.NETWORK, "Network error: " + e.getMessage(), e);
        }
        if (r.status == 404) throw new ResolveException(Reason.NOT_FOUND, "Folder " + folderId + " not found");
        String fin = r.finalUrl == null ? "" : r.finalUrl;
        if (fin.contains("accounts.google.com") || fin.contains("ServiceLogin")) {
            throw new ResolveException(Reason.PRIVATE, "Folder " + folderId + " is not shared with 'anyone with the link'");
        }
        String html = r.body();
        if (top) {
            String title = Html.title(html);
            if (title != null && !title.isEmpty() && !title.equalsIgnoreCase("Google Drive")) out.packageName(title);
        }

        List<int[]> spans = new ArrayList<>();
        Matcher m = ENTRY.matcher(html);
        List<String> ids = new ArrayList<>();
        while (m.find()) {
            spans.add(new int[]{m.start()});
            ids.add(m.group(1));
        }
        for (int i = 0; i < spans.size(); i++) {
            int start = spans.get(i)[0];
            int end = i + 1 < spans.size() ? spans.get(i + 1)[0] : html.length();
            String seg = html.substring(start, end);
            String id = ids.get(i);
            Matcher hm = HREF.matcher(seg);
            String href = hm.find() ? Html.unescape(hm.group(1)) : "";
            Matcher tm = ENTRY_TITLE.matcher(seg);
            String name = tm.find() ? Html.unescape(tm.group(1).trim()) : id;
            if (href.contains("/folders/")) {
                if (depth > 0) {
                    listScrape(ctx, id, join(path, name), depth - 1, out, false);
                } else {
                    out.warn("Subfolder '" + name + "' skipped: gdrive.maxDepth reached");
                }
            } else {
                out.add(ResolvedFile.link(GoogleDriveUrls.fileViewUrl(id)).name(name).path(path));
            }
        }
        if (ids.size() >= SCRAPE_TRUNCATION_HINT) {
            out.warn("Folder '" + (path.isEmpty() ? folderId : path) + "' shows " + ids.size()
                    + " entries; Drive's embedded view probably truncated it. Set gdrive.apiKey for a full listing.");
        }
    }

    private static String join(String a, String b) {
        return a.isEmpty() ? b : a + "/" + b;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
