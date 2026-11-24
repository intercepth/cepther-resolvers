package com.cepther.resolvers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** What a resolver returns: files plus non-fatal warnings (e.g. "listing truncated"). */
public final class ResolveResult {
    private final List<ResolvedFile> files = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private String packageName;

    public static ResolveResult of(ResolvedFile f) {
        ResolveResult r = new ResolveResult();
        r.add(f);
        return r;
    }

    public ResolveResult add(ResolvedFile f) {
        files.add(f);
        return this;
    }

    public ResolveResult warn(String w) {
        warnings.add(w);
        return this;
    }

    /** Suggested package/folder name for the batch (e.g. the Drive folder's name). */
    public ResolveResult packageName(String n) {
        this.packageName = n;
        return this;
    }

    public List<ResolvedFile> files() { return Collections.unmodifiableList(files); }
    public List<String> warnings() { return Collections.unmodifiableList(warnings); }
    public String packageName() { return packageName; }
}
