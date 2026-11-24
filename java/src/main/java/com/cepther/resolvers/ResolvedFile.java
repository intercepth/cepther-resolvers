package com.cepther.resolvers;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One result row.
 *
 * <ul>
 *   <li>{@code direct == true}: {@link #url()} serves the file bytes (send {@link #headers()} with it).</li>
 *   <li>{@code direct == false}: {@link #url()} is another page that must be run through the registry
 *       again (e.g. a file inside a Drive folder). {@link ResolverRegistry#resolveAll} does this for you.</li>
 * </ul>
 */
public final class ResolvedFile {
    private final String url;
    private final boolean direct;
    private String name;
    private long size = -1;
    private String path = "";
    private final Map<String, String> headers = new LinkedHashMap<>();

    private ResolvedFile(String url, boolean direct) {
        this.url = url;
        this.direct = direct;
    }

    public static ResolvedFile direct(String url) {
        return new ResolvedFile(url, true);
    }

    public static ResolvedFile link(String url) {
        return new ResolvedFile(url, false);
    }

    public ResolvedFile name(String n) {
        this.name = n;
        return this;
    }

    public ResolvedFile size(long s) {
        this.size = s;
        return this;
    }

    /** Folder path relative to the crawled root, '/' separated, no leading/trailing slash. */
    public ResolvedFile path(String p) {
        this.path = p == null ? "" : p;
        return this;
    }

    /** Extra request header the download needs (Referer, Cookie, ...). */
    public ResolvedFile header(String k, String v) {
        if (k != null && v != null) headers.put(k, v);
        return this;
    }

    public String url() { return url; }
    public boolean isDirect() { return direct; }
    public String name() { return name; }
    /** Bytes, or -1 when unknown. */
    public long size() { return size; }
    public String path() { return path; }
    public Map<String, String> headers() { return Collections.unmodifiableMap(headers); }

    @Override
    public String toString() {
        return (direct ? "FILE " : "LINK ") + (path.isEmpty() ? "" : path + "/")
                + (name == null ? "?" : name) + (size >= 0 ? " (" + size + " B)" : "") + " <" + url + ">";
    }
}
