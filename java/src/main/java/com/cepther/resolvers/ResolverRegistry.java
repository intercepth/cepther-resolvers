package com.cepther.resolvers;

import com.cepther.resolvers.manifest.ManifestResolver;
import com.cepther.resolvers.sites.DropboxResolver;
import com.cepther.resolvers.sites.GoogleDocsResolver;
import com.cepther.resolvers.sites.GoogleDriveFileResolver;
import com.cepther.resolvers.sites.GoogleDriveFolderResolver;
import com.cepther.resolvers.sites.MediaFireResolver;
import com.cepther.resolvers.sites.PixeldrainResolver;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Ordered list of resolvers; first match wins. Hand-written resolvers go before manifest ones. */
public final class ResolverRegistry {
    private final List<Resolver> resolvers = new ArrayList<>();

    public ResolverRegistry register(Resolver r) {
        resolvers.add(r);
        return this;
    }

    /** All built-in site resolvers (no manifest). */
    public static ResolverRegistry withDefaults() {
        return new ResolverRegistry()
                .register(new GoogleDriveFolderResolver())
                .register(new GoogleDriveFileResolver())
                .register(new GoogleDocsResolver())
                .register(new DropboxResolver())
                .register(new PixeldrainResolver())
                .register(new MediaFireResolver());
    }

    /** Appends data-driven resolvers from a manifest.json body (invalid entries are skipped). */
    public ResolverRegistry addManifest(String manifestJson) {
        for (Resolver r : ManifestResolver.fromJson(manifestJson)) register(r);
        return this;
    }

    public Resolver find(URI url) {
        for (Resolver r : resolvers) {
            if (r.matches(url)) return r;
        }
        return null;
    }

    public List<Resolver> all() {
        return new ArrayList<>(resolvers);
    }

    /** One step: run the matching resolver once. Results may still contain non-direct links. */
    public ResolveResult resolve(String url, ResolveContext ctx) throws ResolveException {
        URI uri = parse(url);
        Resolver r = find(uri);
        if (r == null) {
            throw new ResolveException(ResolveException.Reason.UNSUPPORTED, "No resolver for " + uri.getHost());
        }
        ctx.log("[" + r.name() + "] " + url);
        return r.resolve(uri, ctx);
    }

    /**
     * Full crawl: follows non-direct links (folder entries, redirectors) until everything is a direct file,
     * up to {@code maxDepth} levels. A failing child becomes a warning; a failing top-level URL throws.
     */
    public ResolveResult resolveAll(String url, ResolveContext ctx, int maxDepth) throws ResolveException {
        ResolveResult out = new ResolveResult();
        expand(url, ctx, maxDepth, new HashSet<String>(), out, true);
        return out;
    }

    private void expand(String url, ResolveContext ctx, int depth, Set<String> seen,
                        ResolveResult out, boolean top) throws ResolveException {
        if (!seen.add(url)) return;
        ResolveResult res = resolve(url, ctx);
        if (top) out.packageName(res.packageName());
        for (String w : res.warnings()) out.warn(w);
        for (ResolvedFile f : res.files()) {
            if (f.isDirect()) {
                out.add(f);
                continue;
            }
            if (depth <= 0) {
                out.add(f);
                out.warn("Depth limit reached, left unresolved: " + f.url());
                continue;
            }
            ResolveResult sub = new ResolveResult();
            try {
                expand(f.url(), ctx, depth - 1, seen, sub, false);
            } catch (ResolveException e) {
                out.warn("Skipped " + (f.name() != null ? f.name() : f.url()) + ": " + e.getMessage());
                continue;
            }
            for (String w : sub.warnings()) out.warn(w);
            for (ResolvedFile sf : sub.files()) {
                if (sf.name() == null && f.name() != null) sf.name(f.name());
                if (sf.size() < 0 && f.size() >= 0) sf.size(f.size());
                String p = f.path().isEmpty() ? sf.path()
                        : (sf.path().isEmpty() ? f.path() : f.path() + "/" + sf.path());
                sf.path(p);
                out.add(sf);
            }
        }
    }

    private static URI parse(String url) throws ResolveException {
        try {
            URI u = URI.create(url.trim());
            if (u.getHost() == null) throw new IllegalArgumentException("no host");
            return u;
        } catch (IllegalArgumentException e) {
            throw new ResolveException(ResolveException.Reason.UNSUPPORTED, "Not a valid URL: " + url, e);
        }
    }
}
