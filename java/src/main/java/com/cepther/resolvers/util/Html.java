package com.cepther.resolvers.util;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Regex-level HTML helpers. Deliberately small: good enough for link/form hosts, not a DOM. */
public final class Html {
    private static final int CI = Pattern.CASE_INSENSITIVE | Pattern.DOTALL;
    private static final Pattern A_HREF = Pattern.compile("<a\\b[^>]*?\\bhref\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", CI);
    private static final Pattern FORM = Pattern.compile("<form\\b([^>]*)>(.*?)</form>", CI);
    private static final Pattern INPUT = Pattern.compile("<input\\b([^>]*)>", CI);
    private static final Pattern TITLE = Pattern.compile("<title[^>]*>(.*?)</title>", CI);

    private Html() { }

    public static String unescape(String s) {
        if (s == null || s.indexOf('&') < 0) return s;
        Matcher m = Pattern.compile("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);").matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String e = m.group(1);
            String r;
            switch (e) {
                case "amp": r = "&"; break;
                case "lt": r = "<"; break;
                case "gt": r = ">"; break;
                case "quot": r = "\""; break;
                case "apos": r = "'"; break;
                default:
                    try {
                        int cp = e.startsWith("#x") ? Integer.parseInt(e.substring(2), 16) : Integer.parseInt(e.substring(1));
                        r = new String(Character.toChars(cp));
                    } catch (IllegalArgumentException ex) {
                        r = m.group();
                    }
            }
            m.appendReplacement(out, Matcher.quoteReplacement(r));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Value of attribute {@code name} inside a tag's attribute text (already HTML-unescaped), or null. */
    public static String attr(String attrs, String name) {
        Matcher m = Pattern.compile("(?:^|\\s)" + Pattern.quote(name) + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", CI)
                .matcher(attrs);
        if (!m.find()) return null;
        String v = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
        return unescape(v);
    }

    public static String title(String html) {
        Matcher m = TITLE.matcher(html);
        return m.find() ? unescape(m.group(1).trim()) : null;
    }

    /** All {@code <a href>} values, unescaped and resolved against {@code base}. */
    public static List<String> hrefs(String html, String base) {
        List<String> out = new ArrayList<>();
        Matcher m = A_HREF.matcher(html);
        while (m.find()) {
            String raw = unescape(m.group(1) != null ? m.group(1) : m.group(2));
            String abs = resolve(base, raw.trim());
            if (abs != null) out.add(abs);
        }
        return out;
    }

    public static String resolve(String base, String ref) {
        try {
            return URI.create(base).resolve(ref).toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static List<Form> forms(String html, String base) {
        List<Form> out = new ArrayList<>();
        Matcher m = FORM.matcher(html);
        while (m.find()) {
            String attrs = m.group(1);
            String inner = m.group(2);
            String action = attr(attrs, "action");
            String abs = (action == null || action.trim().isEmpty()) ? base : resolve(base, action.trim());
            if (abs == null) continue;
            String method = attr(attrs, "method");
            Form f = new Form(attr(attrs, "id"), abs,
                    method == null ? "get" : method.toLowerCase(Locale.ROOT), inner);
            Matcher im = INPUT.matcher(inner);
            while (im.find()) {
                String ia = im.group(1);
                String name = attr(ia, "name");
                if (name == null || name.isEmpty()) continue;
                String type = attr(ia, "type");
                type = type == null ? "text" : type.toLowerCase(Locale.ROOT);
                if (type.equals("submit") || type.equals("button") || type.equals("image")
                        || type.equals("reset") || type.equals("file")) continue;
                if ((type.equals("checkbox") || type.equals("radio")) && !Pattern.compile("\\bchecked\\b", CI).matcher(ia).find()) continue;
                String v = attr(ia, "value");
                f.fields.put(name, v == null ? "" : v);
            }
            out.add(f);
        }
        return out;
    }

    public static final class Form {
        public final String id;
        public final String action;
        public final String method;
        /** Raw inner HTML, for looking at things like captcha widgets inside the form. */
        public final String innerHtml;
        public final Map<String, String> fields = new LinkedHashMap<>();

        Form(String id, String action, String method, String innerHtml) {
            this.id = id;
            this.action = action;
            this.method = method;
            this.innerHtml = innerHtml;
        }

        /** Browser-style GET submission: the action's query is replaced by the form fields. */
        public String submitUrl() {
            String base = action;
            int q = base.indexOf('?');
            if (q >= 0) base = base.substring(0, q);
            int h = base.indexOf('#');
            if (h >= 0) base = base.substring(0, h);
            if (fields.isEmpty()) return base;
            StringJoiner j = new StringJoiner("&");
            for (Map.Entry<String, String> e : fields.entrySet()) {
                j.add(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            }
            return base + "?" + j;
        }
    }
}
