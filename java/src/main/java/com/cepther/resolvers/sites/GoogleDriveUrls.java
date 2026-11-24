package com.cepther.resolvers.sites;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** URL-shape knowledge for Google Drive, shared by the file / folder / docs resolvers. */
final class GoogleDriveUrls {
    private static final String ID = "([A-Za-z0-9_-]{10,})";
    private static final Pattern FILE_PATH = Pattern.compile("/file/(?:u/\\d+/)?d/" + ID);
    private static final Pattern FOLDER_PATH = Pattern.compile("/(?:drive/(?:u/\\d+/)?)?folders/" + ID);
    private static final Pattern ID_PARAM = Pattern.compile("^" + ID + "$");

    private GoogleDriveUrls() { }

    static String host(URI u) {
        return u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
    }

    static boolean isDriveHost(URI u) {
        String h = host(u);
        return h.equals("drive.google.com") || h.equals("drive.usercontent.google.com");
    }

    static Map<String, String> query(URI u) {
        Map<String, String> m = new HashMap<>();
        String q = u.getRawQuery();
        if (q == null) return m;
        for (String part : q.split("&")) {
            int eq = part.indexOf('=');
            String k = eq < 0 ? part : part.substring(0, eq);
            String v = eq < 0 ? "" : part.substring(eq + 1);
            try {
                m.putIfAbsent(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ignored) {
                // skip malformed pair
            }
        }
        return m;
    }

    /** Folder id for /drive/folders/ID, /folders/ID, folderview?id=, embeddedfolderview?id=; else null. */
    static String folderId(URI u) {
        if (!isDriveHost(u)) return null;
        String path = u.getPath() == null ? "" : u.getPath();
        Matcher m = FOLDER_PATH.matcher(path);
        if (m.find()) return m.group(1);
        if (path.endsWith("/folderview") || path.endsWith("/embeddedfolderview")) {
            String id = query(u).get("id");
            if (id != null && ID_PARAM.matcher(id).matches()) return id;
        }
        return null;
    }

    /** File id for /file/d/ID (drive or docs host), uc?id=, open?id=, download?id=; else null. */
    static String fileId(URI u) {
        String h = host(u);
        String path = u.getPath() == null ? "" : u.getPath();
        if (h.equals("drive.google.com") || h.equals("drive.usercontent.google.com") || h.equals("docs.google.com")) {
            Matcher m = FILE_PATH.matcher(path);
            if (m.find()) return m.group(1);
        }
        if (isDriveHost(u) && folderId(u) == null) {
            String p = path.replaceFirst("^/u/\\d+", "");
            if (p.equals("/uc") || p.equals("/open") || p.equals("/download")) {
                String id = query(u).get("id");
                if (id != null && ID_PARAM.matcher(id).matches()) return id;
            }
        }
        return null;
    }

    static String resourceKey(URI u) {
        String rk = query(u).get("resourcekey");
        return rk == null || rk.isEmpty() ? null : rk;
    }

    static String fileViewUrl(String id) {
        return "https://drive.google.com/file/d/" + id + "/view";
    }
}
