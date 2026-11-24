package com.cepther.resolvers;

import com.cepther.resolvers.ResolveException.Reason;
import com.cepther.resolvers.captcha.CaptchaChallenge;
import com.cepther.resolvers.captcha.CaptchaException;
import com.cepther.resolvers.captcha.CaptchaSolver;
import com.cepther.resolvers.captcha.TwoCaptchaSolver;
import com.cepther.resolvers.http.Http;
import com.cepther.resolvers.http.HttpResponse;
import com.cepther.resolvers.manifest.ManifestResolver;
import com.cepther.resolvers.sites.DropboxResolver;
import com.cepther.resolvers.sites.GoogleDocsResolver;
import com.cepther.resolvers.sites.GoogleDriveFileResolver;
import com.cepther.resolvers.sites.GoogleDriveFolderResolver;
import com.cepther.resolvers.sites.MediaFireResolver;
import com.cepther.resolvers.sites.PixeldrainResolver;
import com.cepther.resolvers.util.Html;
import com.cepther.resolvers.util.MiniJson;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline tests (no JUnit, no network). Every site test feeds the resolver a hand-written fixture that mimics
 * the page/API shape the resolver was written against -- so they prove the logic, NOT that the live sites still
 * look like that. Run {@code Cli} against real URLs for that.
 */
public final class TestMain {
    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    // ---------------------------------------------------------------- fake HTTP

    static final class FakeHttp implements Http {
        final Map<String, HttpResponse> routes = new LinkedHashMap<>();
        final List<String> log = new ArrayList<>();
        Map<String, String> lastPostForm;

        FakeHttp on(String url, HttpResponse r) {
            routes.put(url, r);
            return this;
        }

        @Override
        public HttpResponse get(String url, Map<String, String> headers) throws IOException {
            log.add("GET " + url);
            HttpResponse r = routes.get(url);
            if (r == null) throw new IOException("no fake route for GET " + url);
            return r;
        }

        @Override
        public HttpResponse post(String url, Map<String, String> form, Map<String, String> headers) throws IOException {
            log.add("POST " + url);
            lastPostForm = form;
            HttpResponse r = routes.get("POST " + url);
            if (r == null) throw new IOException("no fake route for POST " + url);
            return r;
        }
    }

    static HttpResponse html(String url, String body) {
        return resp(200, url, "text/html; charset=utf-8", null, body);
    }

    static HttpResponse json(String url, String body) {
        return resp(200, url, "application/json", null, body);
    }

    static HttpResponse file(String url, String name, long size) {
        Map<String, String> h = new HashMap<>();
        h.put("content-type", "application/octet-stream");
        h.put("content-disposition", "attachment; filename=\"" + name + "\"");
        h.put("content-length", String.valueOf(size));
        return new HttpResponse(200, url, url, h, null);
    }

    static HttpResponse resp(int status, String url, String ct, String finalUrl, String body) {
        Map<String, String> h = new HashMap<>();
        h.put("content-type", ct);
        return new HttpResponse(status, url, finalUrl, h, body);
    }

    // ---------------------------------------------------------------- tiny assertions

    interface Case {
        void run() throws Exception;
    }

    static void test(String name, Case c) {
        try {
            c.run();
            passed++;
            System.out.println("  ok   " + name);
        } catch (Throwable t) {
            failures.add(name + ": " + t);
            System.out.println("  FAIL " + name + "\n       " + t);
        }
    }

    static void eq(Object expected, Object actual, String what) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    static void isTrue(boolean cond, String what) {
        if (!cond) throw new AssertionError(what);
    }

    static ResolveException expectFail(Reason reason, Case c) throws Exception {
        try {
            c.run();
        } catch (ResolveException e) {
            eq(reason, e.reason(), "failure reason (" + e.getMessage() + ")");
            return e;
        }
        throw new AssertionError("expected ResolveException " + reason + " but nothing was thrown");
    }

    static ResolveContext ctx(Http h) {
        return new ResolveContext(h);
    }

    // ---------------------------------------------------------------- main

    public static void main(String[] args) throws Exception {
        System.out.println("util");
        test("MiniJson parses nested values", () -> {
            Map<String, Object> m = MiniJson.map(MiniJson.parse(
                    "{\"a\":[1,2.5,\"x\\n\\u0041\",true,null],\"b\":{\"c\":-7}}"));
            List<Object> a = MiniJson.list(m.get("a"));
            eq(1L, a.get(0), "int");
            eq(2.5, a.get(1), "double");
            eq("x\nA", a.get(2), "escapes");
            eq(Boolean.TRUE, a.get(3), "bool");
            eq(null, a.get(4), "null");
            eq(-7L, MiniJson.map(m.get("b")).get("c"), "nested");
        });
        test("MiniJson rejects garbage", () -> {
            for (String bad : new String[]{"{", "[1,]x", "{\"a\" 1}", "tru", "{} extra"}) {
                try {
                    MiniJson.parse(bad);
                    throw new AssertionError("accepted: " + bad);
                } catch (IllegalArgumentException expected) {
                    // good
                }
            }
        });
        test("Html forms: hidden fields, GET submit url", () -> {
            String page = "<form id=\"f\" action=\"/dl?x=1\" method=\"GET\"><input type=\"hidden\" name=\"a\" value=\"1&amp;2\">"
                    + "<input name=\"b\" value=\"x y\"><input type=\"submit\" name=\"go\" value=\"Go\"></form>";
            Html.Form f = Html.forms(page, "https://h.example/page").get(0);
            eq("https://h.example/dl?x=1", f.action, "action is kept as written");
            eq("1&2", f.fields.get("a"), "unescaped value");
            isTrue(!f.fields.containsKey("go"), "submit skipped");
            eq("https://h.example/dl?a=1%262&b=x+y", f.submitUrl(), "submit url");
        });
        test("HttpResponse.fileName handles filename* and plain", () -> {
            Map<String, String> h = new HashMap<>();
            h.put("Content-Disposition", "attachment; filename=\"a.zip\"; filename*=UTF-8''b%20c.zip");
            eq("b c.zip", new HttpResponse(200, "u", "u", h, null).fileName(), "star wins");
            h.put("Content-Disposition", "attachment; filename=\"a.zip\"");
            eq("a.zip", new HttpResponse(200, "u", "u", h, null).fileName(), "plain");
        });

        System.out.println("google drive file");
        final String ID = "1AbCdEfGhIjKlMnOpQrStUvWxYz012345";
        test("url shapes", () -> {
            GoogleDriveFileResolver r = new GoogleDriveFileResolver();
            GoogleDriveFolderResolver fr = new GoogleDriveFolderResolver();
            for (String u : new String[]{
                    "https://drive.google.com/file/d/" + ID + "/view?usp=sharing",
                    "https://drive.google.com/file/u/0/d/" + ID + "/edit",
                    "https://drive.google.com/open?id=" + ID,
                    "https://drive.google.com/uc?export=download&id=" + ID,
                    "https://drive.google.com/u/0/uc?id=" + ID,
                    "https://drive.usercontent.google.com/download?id=" + ID + "&export=download",
                    "https://docs.google.com/file/d/" + ID + "/edit"}) {
                isTrue(r.matches(URI.create(u)), "file resolver should match " + u);
                isTrue(!fr.matches(URI.create(u)), "folder resolver must not match " + u);
            }
            for (String u : new String[]{
                    "https://drive.google.com/drive/folders/" + ID + "?usp=sharing",
                    "https://drive.google.com/drive/u/1/folders/" + ID,
                    "https://drive.google.com/folderview?id=" + ID,
                    "https://drive.google.com/embeddedfolderview?id=" + ID + "#list"}) {
                isTrue(fr.matches(URI.create(u)), "folder resolver should match " + u);
                isTrue(!r.matches(URI.create(u)), "file resolver must not match " + u);
            }
            isTrue(!r.matches(URI.create("https://drive.google.com/drive/my-drive")), "no match for my-drive");
            isTrue(!r.matches(URI.create("https://example.com/file/d/" + ID)), "no match on foreign host");
        });
        test("small file served directly", () -> {
            String start = "https://drive.google.com/uc?export=download&id=" + ID;
            FakeHttp http = new FakeHttp().on(start, file(start, "pic.png", 1234));
            ResolvedFile f = new GoogleDriveFileResolver()
                    .resolve(URI.create("https://drive.google.com/file/d/" + ID + "/view"), ctx(http)).files().get(0);
            isTrue(f.isDirect(), "direct");
            eq("pic.png", f.name(), "name");
            eq(1234L, f.size(), "size");
            eq(start, f.url(), "url");
        });
        test("large file: follows virus-scan confirm form", () -> {
            String start = "https://drive.google.com/uc?export=download&id=" + ID;
            String form = "<html><body><form id=\"download-form\" action=\"https://drive.usercontent.google.com/download\" method=\"get\">"
                    + "<input type=\"hidden\" name=\"id\" value=\"" + ID + "\"><input type=\"hidden\" name=\"export\" value=\"download\">"
                    + "<input type=\"hidden\" name=\"confirm\" value=\"t\"><input type=\"hidden\" name=\"uuid\" value=\"abc-123\">"
                    + "<input type=\"submit\" value=\"Download anyway\"></form></body></html>";
            String second = "https://drive.usercontent.google.com/download?id=" + ID + "&export=download&confirm=t&uuid=abc-123";
            FakeHttp http = new FakeHttp().on(start, html(start, form)).on(second, file(second, "big.iso", 5_000_000_000L));
            ResolvedFile f = new GoogleDriveFileResolver()
                    .resolve(URI.create("https://drive.google.com/open?id=" + ID), ctx(http)).files().get(0);
            eq(second, f.url(), "url is the confirmed link");
            eq("big.iso", f.name(), "name");
            eq(5_000_000_000L, f.size(), "size > 4GiB");
        });
        test("legacy confirm-token link still works", () -> {
            String start = "https://drive.google.com/uc?export=download&id=" + ID;
            String page = "<a href=\"/uc?export=download&amp;confirm=AbC1&amp;id=" + ID + "\">Download anyway</a>";
            String second = "https://drive.google.com/uc?export=download&confirm=AbC1&id=" + ID;
            FakeHttp http = new FakeHttp().on(start, html(start, page)).on(second, file(second, "x.bin", 9));
            eq(second, new GoogleDriveFileResolver()
                    .resolve(URI.create("https://drive.google.com/open?id=" + ID), ctx(http)).files().get(0).url(), "url");
        });
        test("quota exceeded / private / missing are typed errors", () -> {
            final String start = "https://drive.google.com/uc?export=download&id=" + ID;
            final URI in = URI.create("https://drive.google.com/open?id=" + ID);
            final FakeHttp quota = new FakeHttp().on(start, html(start,
                    "<p>Sorry, you can't view or download this file at this time. Too many users have viewed or downloaded this file recently.</p>"));
            expectFail(Reason.QUOTA_EXCEEDED, () -> new GoogleDriveFileResolver().resolve(in, ctx(quota)));
            final FakeHttp priv = new FakeHttp().on(start, resp(200, start, "text/html", "https://accounts.google.com/ServiceLogin?x=1", "<html>Sign in</html>"));
            expectFail(Reason.PRIVATE, () -> new GoogleDriveFileResolver().resolve(in, ctx(priv)));
            final FakeHttp gone = new FakeHttp().on(start, resp(404, start, "text/html", null, "<html>nope</html>"));
            expectFail(Reason.NOT_FOUND, () -> new GoogleDriveFileResolver().resolve(in, ctx(gone)));
            final FakeHttp weird = new FakeHttp().on(start, html(start, "<html><title>Something new</title></html>"));
            expectFail(Reason.PARSE_FAILED, () -> new GoogleDriveFileResolver().resolve(in, ctx(weird)));
        });
        test("endless confirm loop is bounded", () -> {
            final String start = "https://drive.google.com/uc?export=download&id=" + ID;
            String form = "<form id=\"download-form\" action=\"" + start + "\"><input type=\"hidden\" name=\"confirm\" value=\"t\"></form>";
            final String again = start.substring(0, start.indexOf('?')) + "?confirm=t";
            final FakeHttp http = new FakeHttp().on(start, html(start, form)).on(again, html(again, form));
            expectFail(Reason.PARSE_FAILED, () -> new GoogleDriveFileResolver()
                    .resolve(URI.create("https://drive.google.com/open?id=" + ID), ctx(http)));
        });

        System.out.println("google drive folder");
        final String ROOT = "1RootFolderIdAAAAAAAAAAAAAAAAAAA";
        final String SUB = "1SubFolderIdBBBBBBBBBBBBBBBBBBBB";
        test("API mode: pagination, recursion, sizes, native docs, package name", () -> {
            String api = "https://www.googleapis.com/drive/v3/files";
            String fields = "nextPageToken%2Cfiles%28id%2Cname%2CmimeType%2Csize%29";
            String tail = "&pageSize=1000&supportsAllDrives=true&includeItemsFromAllDrives=true&key=KEY";
            String q = "?q=%27" + ROOT + "%27+in+parents+and+trashed%3Dfalse&fields=" + fields + tail;
            String qSub = "?q=%27" + SUB + "%27+in+parents+and+trashed%3Dfalse&fields=" + fields + tail;
            FakeHttp http = new FakeHttp()
                    .on(api + "/" + ROOT + "?fields=name&supportsAllDrives=true&key=KEY", json("x", "{\"name\":\"My Pack\"}"))
                    .on(api + q, json("x", "{\"nextPageToken\":\"P2\",\"files\":["
                            + "{\"id\":\"fileA00000000\",\"name\":\"a.zip\",\"mimeType\":\"application/zip\",\"size\":\"100\"},"
                            + "{\"id\":\"" + SUB + "\",\"name\":\"Sub\",\"mimeType\":\"application/vnd.google-apps.folder\"}]}"))
                    .on(api + q + "&pageToken=P2", json("x", "{\"files\":["
                            + "{\"id\":\"docDoc00000000\",\"name\":\"Notes\",\"mimeType\":\"application/vnd.google-apps.document\"},"
                            + "{\"id\":\"formForm000000\",\"name\":\"Survey\",\"mimeType\":\"application/vnd.google-apps.form\"}]}"))
                    .on(api + qSub, json("x", "{\"files\":[{\"id\":\"fileB00000000\",\"name\":\"b.txt\",\"mimeType\":\"text/plain\",\"size\":\"7\"}]}"));
            ResolveResult r = new GoogleDriveFolderResolver().resolve(
                    URI.create("https://drive.google.com/drive/folders/" + ROOT), ctx(http).option("gdrive.apiKey", "KEY"));
            eq("My Pack", r.packageName(), "package name");
            List<ResolvedFile> fs = r.files();
            eq(3, fs.size(), "3 downloadable entries (form skipped)");
            eq("a.zip", fs.get(0).name(), "a");
            eq(100L, fs.get(0).size(), "a size");
            eq("", fs.get(0).path(), "a path");
            eq("https://drive.google.com/file/d/fileA00000000/view", fs.get(0).url(), "a url");
            eq("b.txt", fs.get(1).name(), "b");
            eq("Sub", fs.get(1).path(), "b path");
            eq("https://docs.google.com/document/d/docDoc00000000/edit", fs.get(2).url(), "doc url");
            isTrue(r.warnings().size() == 1 && r.warnings().get(0).contains("Survey"), "form skipped with a warning");
        });
        test("API mode: error mapping", () -> {
            final String api = "https://www.googleapis.com/drive/v3/files";
            final String url = api + "?q=%27" + ROOT + "%27+in+parents+and+trashed%3Dfalse&fields=nextPageToken%2Cfiles%28id%2Cname%2CmimeType%2Csize%29"
                    + "&pageSize=1000&supportsAllDrives=true&includeItemsFromAllDrives=true&key=KEY";
            final URI in = URI.create("https://drive.google.com/drive/folders/" + ROOT);
            final FakeHttp nf = new FakeHttp().on(url, json("x", "{\"error\":{\"code\":404,\"message\":\"File not found\"}}"));
            expectFail(Reason.NOT_FOUND, () -> new GoogleDriveFolderResolver().resolve(in, ctx(nf).option("gdrive.apiKey", "KEY")));
            final FakeHttp denied = new FakeHttp().on(url, json("x", "{\"error\":{\"code\":403,\"message\":\"nope\"}}"));
            expectFail(Reason.PRIVATE, () -> new GoogleDriveFolderResolver().resolve(in, ctx(denied).option("gdrive.apiKey", "KEY")));
        });
        test("scrape mode: files, subfolder recursion, truncation warning", () -> {
            String root = "https://drive.google.com/embeddedfolderview?id=" + ROOT;
            String sub = "https://drive.google.com/embeddedfolderview?id=" + SUB;
            String rootHtml = "<html><head><title>Cool Folder</title></head><body>"
                    + "<div class=\"flip-entry\" id=\"entry-fileA00000000\"><div class=\"flip-entry-info\">"
                    + "<a href=\"https://drive.google.com/file/d/fileA00000000/view?usp=drive_web\" target=\"_blank\">"
                    + "<div class=\"flip-entry-title\">a &amp; b.zip</div></a></div></div>"
                    + "<div class=\"flip-entry\" id=\"entry-" + SUB + "\"><div class=\"flip-entry-info\">"
                    + "<a href=\"https://drive.google.com/drive/folders/" + SUB + "\" target=\"_blank\">"
                    + "<div class=\"flip-entry-title\">Inner</div></a></div></div></body></html>";
            String subHtml = "<html><title>Inner</title><div id=\"entry-fileB00000000\"><a href=\"https://drive.google.com/file/d/fileB00000000/view\">"
                    + "<div class=\"flip-entry-title\">b.txt</div></a></div></html>";
            FakeHttp http = new FakeHttp().on(root, html(root, rootHtml)).on(sub, html(sub, subHtml));
            ResolveResult r = new GoogleDriveFolderResolver().resolve(
                    URI.create("https://drive.google.com/drive/folders/" + ROOT), ctx(http));
            eq("Cool Folder", r.packageName(), "package");
            eq(2, r.files().size(), "files");
            eq("a & b.zip", r.files().get(0).name(), "unescaped name");
            eq("Inner", r.files().get(1).path(), "subfolder path");
            isTrue(r.warnings().get(0).contains("gdrive.apiKey"), "warns about scrape mode");

            StringBuilder big = new StringBuilder("<html><title>Big</title>");
            for (int i = 0; i < 50; i++) {
                big.append("<div id=\"entry-file").append(String.format("%012d", i)).append("\"><a href=\"https://drive.google.com/file/d/f")
                        .append(i).append("/view\"><div class=\"flip-entry-title\">f").append(i).append("</div></a></div>");
            }
            FakeHttp http2 = new FakeHttp().on(root, html(root, big.toString()));
            ResolveResult r2 = new GoogleDriveFolderResolver().resolve(
                    URI.create("https://drive.google.com/drive/folders/" + ROOT), ctx(http2));
            eq(50, r2.files().size(), "50 files");
            isTrue(r2.warnings().toString().contains("truncated"), "truncation warning: " + r2.warnings());
        });
        test("scrape mode: login wall -> PRIVATE", () -> {
            final String root = "https://drive.google.com/embeddedfolderview?id=" + ROOT;
            final FakeHttp http = new FakeHttp().on(root, resp(200, root, "text/html", "https://accounts.google.com/ServiceLogin", "<html></html>"));
            expectFail(Reason.PRIVATE, () -> new GoogleDriveFolderResolver()
                    .resolve(URI.create("https://drive.google.com/drive/folders/" + ROOT), ctx(http)));
        });
        test("maxDepth stops recursion with a warning", () -> {
            String root = "https://drive.google.com/embeddedfolderview?id=" + ROOT;
            String rootHtml = "<html><div id=\"entry-" + SUB + "\"><a href=\"https://drive.google.com/drive/folders/" + SUB
                    + "\"><div class=\"flip-entry-title\">Inner</div></a></div></html>";
            FakeHttp http = new FakeHttp().on(root, html(root, rootHtml));
            ResolveResult r = new GoogleDriveFolderResolver().resolve(
                    URI.create("https://drive.google.com/drive/folders/" + ROOT), ctx(http).option("gdrive.maxDepth", "0"));
            isTrue(r.warnings().toString().contains("maxDepth"), "depth warning: " + r.warnings());
        });

        System.out.println("google docs");
        test("native doc/sheet/slides export urls and format override", () -> {
            String d = "https://docs.google.com/document/d/" + ID + "/export?format=docx";
            String s = "https://docs.google.com/spreadsheets/d/" + ID + "/export?format=pdf";
            String p = "https://docs.google.com/presentation/d/" + ID + "/export/pptx";
            FakeHttp http = new FakeHttp().on(d, file(d, "Doc.docx", 10)).on(s, file(s, "Sheet.pdf", 11)).on(p, file(p, "Deck.pptx", 12));
            GoogleDocsResolver r = new GoogleDocsResolver();
            eq(d, r.resolve(URI.create("https://docs.google.com/document/d/" + ID + "/edit"), ctx(http)).files().get(0).url(), "doc");
            eq(s, r.resolve(URI.create("https://docs.google.com/spreadsheets/d/" + ID + "/edit#gid=0"), ctx(http).option("gdocs.format", "pdf")).files().get(0).url(), "sheet");
            eq(p, r.resolve(URI.create("https://docs.google.com/presentation/d/" + ID + "/edit"), ctx(http)).files().get(0).url(), "slides");
            isTrue(!r.matches(URI.create("https://docs.google.com/file/d/" + ID)), "file/d is Drive, not Docs");
        });
        test("non-shared doc -> PRIVATE", () -> {
            final String d = "https://docs.google.com/document/d/" + ID + "/export?format=docx";
            final FakeHttp http = new FakeHttp().on(d, resp(200, d, "text/html", "https://accounts.google.com/ServiceLogin", "<html/>"));
            expectFail(Reason.PRIVATE, () -> new GoogleDocsResolver()
                    .resolve(URI.create("https://docs.google.com/document/d/" + ID + "/edit"), ctx(http)));
        });

        System.out.println("dropbox");
        test("dl=1 rewrite keeps rlkey, drops dl/raw", () -> {
            String in = "https://www.dropbox.com/scl/fi/abc123/My%20File.zip?rlkey=KEY&st=xyz&dl=0";
            String out = "https://www.dropbox.com/scl/fi/abc123/My%20File.zip?rlkey=KEY&st=xyz&dl=1";
            FakeHttp http = new FakeHttp().on(out, file(out, "My File.zip", 77));
            ResolvedFile f = new DropboxResolver().resolve(URI.create(in), ctx(http)).files().get(0);
            eq(out, f.url(), "url");
            eq("My File.zip", f.name(), "name from header");
            eq("https://www.dropbox.com/s/abc/f.txt?dl=1", DropboxResolver.withDl1(URI.create("https://www.dropbox.com/s/abc/f.txt?raw=1&dl=0")), "raw/dl dropped");
        });
        test("dropbox: password and 404", () -> {
            final String u = "https://www.dropbox.com/s/abc/f.txt?dl=1";
            final URI in = URI.create("https://www.dropbox.com/s/abc/f.txt");
            final FakeHttp pw = new FakeHttp().on(u, html(u, "<html>Enter the password to continue</html>"));
            expectFail(Reason.LOGIN_REQUIRED, () -> new DropboxResolver().resolve(in, ctx(pw)));
            final FakeHttp nf = new FakeHttp().on(u, resp(404, u, "text/html", null, "<html>Error (404)</html>"));
            expectFail(Reason.NOT_FOUND, () -> new DropboxResolver().resolve(in, ctx(nf)));
            isTrue(!new DropboxResolver().matches(URI.create("https://www.dropbox.com/home")), "/home not a share link");
        });

        System.out.println("pixeldrain");
        test("file and list", () -> {
            FakeHttp http = new FakeHttp()
                    .on("https://pixeldrain.com/api/file/AbCd1234/info", json("x", "{\"success\":true,\"id\":\"AbCd1234\",\"name\":\"clip.mp4\",\"size\":4096}"))
                    .on("https://pixeldrain.com/api/list/LiSt99", json("x", "{\"success\":true,\"title\":\"Album\",\"files\":["
                            + "{\"id\":\"f1\",\"name\":\"1.jpg\",\"size\":1},{\"id\":\"f2\",\"name\":\"2.jpg\",\"size\":2}]}"));
            PixeldrainResolver r = new PixeldrainResolver();
            ResolvedFile f = r.resolve(URI.create("https://pixeldrain.com/u/AbCd1234"), ctx(http)).files().get(0);
            eq("https://pixeldrain.com/api/file/AbCd1234?download", f.url(), "url");
            eq("clip.mp4", f.name(), "name");
            eq(4096L, f.size(), "size");
            ResolveResult l = r.resolve(URI.create("https://pixeldrain.com/l/LiSt99#item=1"), ctx(http));
            eq("Album", l.packageName(), "list title");
            eq(2, l.files().size(), "list size");
            isTrue(l.files().get(1).isDirect(), "list entries are direct");
        });
        test("not found", () -> {
            final FakeHttp http = new FakeHttp().on("https://pixeldrain.com/api/file/Nope/info",
                    resp(404, "x", "application/json", null, "{\"success\":false,\"value\":\"file_not_found\",\"message\":\"gone\"}"));
            expectFail(Reason.NOT_FOUND, () -> new PixeldrainResolver().resolve(URI.create("https://pixeldrain.com/u/Nope"), ctx(http)));
        });

        System.out.println("mediafire");
        test("plain button href, scrambled url, fallback scan", () -> {
            String page = "https://www.mediafire.com/file/k3y/My_File.zip/file";
            String real = "https://download1234.mediafire.com/abc/k3y/My_File.zip";
            FakeHttp a = new FakeHttp().on(page, html(page, "<a class=\"input popsok\" aria-label=\"Download file\" id=\"downloadButton\" href=\"" + real + "\">Download</a>"));
            eq(real, new MediaFireResolver().resolve(URI.create(page), ctx(a)).files().get(0).url(), "plain");
            eq("My_File.zip", new MediaFireResolver().resolve(URI.create(page), ctx(a)).files().get(0).name(), "name");

            String b64 = java.util.Base64.getEncoder().encodeToString(real.getBytes(StandardCharsets.UTF_8));
            FakeHttp b = new FakeHttp().on(page, html(page, "<a id=\"downloadButton\" href=\"javascript:void(0)\" data-scrambled-url=\"" + b64 + "\">Download</a>"));
            eq(real, new MediaFireResolver().resolve(URI.create(page), ctx(b)).files().get(0).url(), "scrambled");

            FakeHttp c = new FakeHttp().on(page, html(page, "<a href=\"/x\">x</a><a href=\"" + real + "\">dl</a>"));
            eq(real, new MediaFireResolver().resolve(URI.create(page), ctx(c)).files().get(0).url(), "fallback");
        });
        test("rejects links on other hosts; deleted file", () -> {
            final String page = "https://www.mediafire.com/file/k3y/f.zip/file";
            final FakeHttp evil = new FakeHttp().on(page, html(page, "<a id=\"downloadButton\" href=\"https://evil.example/f.zip\">x</a>"));
            expectFail(Reason.PARSE_FAILED, () -> new MediaFireResolver().resolve(URI.create(page), ctx(evil)));
            final FakeHttp gone = new FakeHttp().on(page, html(page, "<p>Invalid or Deleted File.</p>"));
            expectFail(Reason.NOT_FOUND, () -> new MediaFireResolver().resolve(URI.create(page), ctx(gone)));
        });

        System.out.println("manifest + captcha");
        test("repo manifest.json loads into resolvers", () -> {
            String json = new String(Files.readAllBytes(Paths.get(args.length > 0 ? args[0] : "../manifest.json")), StandardCharsets.UTF_8);
            eq(2, ManifestResolver.fromJson(json).size(), "entries in manifest.json");
        });
        test("manifest: bad entries skipped, wrong schema rejected", () -> {
            String json = "{\"schema_version\":1,\"hosts\":[{\"host\":\"a.com\",\"kind\":\"direct_link\",\"link_host_pattern\":\"(\"},"
                    + "{\"host\":\"b.com\",\"kind\":\"teleport\"},{\"host\":\"c.com\",\"kind\":\"direct_link\",\"link_host_pattern\":\"^cdn\\\\.c\\\\.com$\"}]}";
            eq(1, ManifestResolver.fromJson(json).size(), "only the valid entry");
            eq(0, ManifestResolver.fromJson("{\"schema_version\":2,\"hosts\":[]}").size(), "schema 2");
            eq(0, ManifestResolver.fromJson("not json").size(), "garbage");
        });
        test("manifest direct_link", () -> {
            String json = "{\"schema_version\":1,\"hosts\":[{\"host\":\"files.example\",\"kind\":\"direct_link\",\"link_host_pattern\":\"^cdn\\\\d*\\\\.files\\\\.example$\"}]}";
            Resolver r = ManifestResolver.fromJson(json).get(0);
            String page = "https://files.example/d/xyz";
            FakeHttp http = new FakeHttp().on(page, html(page, "<a href=\"/about\">about</a><a href=\"https://cdn7.files.example/get/xyz/a.bin\">get</a>"));
            isTrue(r.matches(URI.create(page)), "matches host");
            isTrue(!r.matches(URI.create("https://evilfiles.example/d/xyz")), "no suffix confusion");
            eq("https://cdn7.files.example/get/xyz/a.bin", r.resolve(URI.create(page), ctx(http)).files().get(0).url(), "link");
        });
        test("manifest form_follow with reCAPTCHA token", () -> {
            String json = "{\"schema_version\":1,\"hosts\":[{\"host\":\"locker.example\",\"kind\":\"form_follow\","
                    + "\"id_pattern\":\"/f/([a-z0-9]+)\",\"first_url_template\":\"https://locker.example/confirm/{id}\","
                    + "\"form_match\":{\"any_field\":\"token\",\"action_contains\":\"download\"}}]}";
            Resolver r = ManifestResolver.fromJson(json).get(0);
            String first = "https://locker.example/confirm/abc1";
            String form = "<form action=\"/download\" method=\"get\"><input type=\"hidden\" name=\"token\" value=\"T\">"
                    + "<div class=\"g-recaptcha\" data-sitekey=\"SITEKEY1\"></div></form>";
            String submit = "https://locker.example/download?token=T&g-recaptcha-response=SOLVED";
            FakeHttp http = new FakeHttp().on(first, html(first, form)).on(submit, file(submit, "z.7z", 5));
            final List<CaptchaChallenge> seen = new ArrayList<>();
            ResolveContext c = ctx(http).captcha(ch -> { seen.add(ch); return "SOLVED"; });
            ResolvedFile f = r.resolve(URI.create("https://locker.example/f/abc1"), c).files().get(0);
            eq(submit, f.url(), "submitted url carries the token");
            eq("z.7z", f.name(), "name");
            eq(1, seen.size(), "solver asked once");
            eq(CaptchaChallenge.Type.RECAPTCHA_V2, seen.get(0).type(), "type");
            eq("SITEKEY1", seen.get(0).siteKey(), "site key");
            eq(first, seen.get(0).pageUrl(), "page url");
        });
        test("captcha with no solver -> CAPTCHA_REQUIRED (not a silent skip)", () -> {
            String json = "{\"schema_version\":1,\"hosts\":[{\"host\":\"locker.example\",\"kind\":\"form_follow\","
                    + "\"id_pattern\":\"/f/([a-z0-9]+)\",\"first_url_template\":\"https://locker.example/confirm/{id}\","
                    + "\"form_match\":{\"any_field\":\"token\"}}]}";
            final Resolver r = ManifestResolver.fromJson(json).get(0);
            String first = "https://locker.example/confirm/abc1";
            final FakeHttp http = new FakeHttp().on(first, html(first,
                    "<form action=\"/d\"><input type=\"hidden\" name=\"token\" value=\"T\"><div class=\"h-captcha\" data-sitekey=\"HK\"></div></form>"));
            expectFail(Reason.CAPTCHA_REQUIRED, () -> r.resolve(URI.create("https://locker.example/f/abc1"), ctx(http)));
        });
        test("solver chain: skips decliners, falls through failures", () -> {
            CaptchaChallenge ch = CaptchaChallenge.widget(CaptchaChallenge.Type.TURNSTILE, "https://p", "SK");
            CaptchaSolver decline = c -> null;
            CaptchaSolver boom = c -> { throw new CaptchaException("no balance"); };
            CaptchaSolver ok = c -> "TOKEN";
            eq("TOKEN", CaptchaSolver.chain(decline, boom, ok).solve(ch), "reaches ok");
            try {
                CaptchaSolver.chain(decline, boom).solve(ch);
                throw new AssertionError("should rethrow last failure");
            } catch (CaptchaException expected) {
                eq("no balance", expected.getMessage(), "last failure surfaced");
            }
            eq(null, CaptchaSolver.chain(decline).solve(ch), "all decline -> null");
        });
        test("TwoCaptchaSolver: submit, poll until ready", () -> {
            FakeHttp http = new FakeHttp()
                    .on("POST https://cap.example/in.php", json("x", "{\"status\":1,\"request\":\"77\"}"));
            final int[] polls = {0};
            Http counting = new Http() {
                @Override public HttpResponse get(String url, Map<String, String> h) throws IOException {
                    isTrue(url.startsWith("https://cap.example/res.php?key=K%26&action=get&json=1&id=77"), "poll url: " + url);
                    return json(url, polls[0]++ < 2 ? "{\"status\":0,\"request\":\"CAPCHA_NOT_READY\"}" : "{\"status\":1,\"request\":\"TOK\"}");
                }
                @Override public HttpResponse post(String url, Map<String, String> f, Map<String, String> h) throws IOException {
                    return http.post(url, f, h);
                }
            };
            final long[] slept = {0};
            TwoCaptchaSolver s = new TwoCaptchaSolver(counting, "K&", "https://cap.example/", 1000, 60_000, ms -> slept[0] += ms);
            eq("TOK", s.solve(CaptchaChallenge.widget(CaptchaChallenge.Type.RECAPTCHA_V2, "https://p", "SK")), "token");
            eq("userrecaptcha", http.lastPostForm.get("method"), "method");
            eq("SK", http.lastPostForm.get("googlekey"), "googlekey");
            eq("https://p", http.lastPostForm.get("pageurl"), "pageurl");
            eq(3000L, slept[0], "polled 3 times");
        });
        test("TwoCaptchaSolver: rejection, error, image, timeout", () -> {
            final CaptchaChallenge w = CaptchaChallenge.widget(CaptchaChallenge.Type.HCAPTCHA, "https://p", "SK");
            FakeHttp bad = new FakeHttp().on("POST https://cap.example/in.php", json("x", "{\"status\":0,\"request\":\"ERROR_WRONG_USER_KEY\"}"));
            try {
                new TwoCaptchaSolver(bad, "K", "https://cap.example", 1, 10, ms -> { }).solve(w);
                throw new AssertionError("expected CaptchaException");
            } catch (CaptchaException e) {
                isTrue(e.getMessage().contains("ERROR_WRONG_USER_KEY"), e.getMessage());
            }
            FakeHttp img = new FakeHttp().on("POST https://cap.example/in.php", json("x", "{\"status\":1,\"request\":\"5\"}"))
                    .on("https://cap.example/res.php?key=K&action=get&json=1&id=5", json("x", "{\"status\":1,\"request\":\"abcd\"}"));
            eq("abcd", new TwoCaptchaSolver(img, "K", "https://cap.example", 1, 10, ms -> { })
                    .solve(CaptchaChallenge.image("https://p", new byte[]{1, 2, 3})), "image answer");
            eq("base64", img.lastPostForm.get("method"), "image method");
            eq("AQID", img.lastPostForm.get("body"), "image body");
            FakeHttp slow = new FakeHttp().on("POST https://cap.example/in.php", json("x", "{\"status\":1,\"request\":\"9\"}"))
                    .on("https://cap.example/res.php?key=K&action=get&json=1&id=9", json("x", "{\"status\":0,\"request\":\"CAPCHA_NOT_READY\"}"));
            try {
                new TwoCaptchaSolver(slow, "K", "https://cap.example", 5, 10, ms -> { }).solve(w);
                throw new AssertionError("expected timeout");
            } catch (CaptchaException e) {
                isTrue(e.getMessage().contains("timed out"), e.getMessage());
            }
        });

        System.out.println("registry");
        test("resolveAll expands a folder into direct files with paths", () -> {
            String root = "https://drive.google.com/embeddedfolderview?id=" + ROOT;
            String rootHtml = "<html><title>Pack</title><div id=\"entry-fileA00000000\"><a href=\"https://drive.google.com/file/d/fileA00000000/view\">"
                    + "<div class=\"flip-entry-title\">a.zip</div></a></div>"
                    + "<div id=\"entry-fileX00000000\"><a href=\"https://drive.google.com/file/d/fileX00000000/view\"><div class=\"flip-entry-title\">gone.zip</div></a></div></html>";
            String ua = "https://drive.google.com/uc?export=download&id=fileA00000000";
            String ux = "https://drive.google.com/uc?export=download&id=fileX00000000";
            FakeHttp http = new FakeHttp().on(root, html(root, rootHtml)).on(ua, file(ua, "a.zip", 42))
                    .on(ux, resp(404, ux, "text/html", null, "x"));
            ResolveResult r = ResolverRegistry.withDefaults().resolveAll("https://drive.google.com/drive/folders/" + ROOT, ctx(http), 3);
            eq("Pack", r.packageName(), "package");
            eq(1, r.files().size(), "one resolvable file");
            isTrue(r.files().get(0).isDirect(), "direct");
            eq(42L, r.files().get(0).size(), "size");
            isTrue(r.warnings().toString().contains("gone.zip"), "failed child reported: " + r.warnings());
        });
        test("unknown host -> UNSUPPORTED; top-level failure throws", () -> {
            final ResolveContext c = ctx(new FakeHttp());
            expectFail(Reason.UNSUPPORTED, () -> ResolverRegistry.withDefaults().resolve("https://nowhere.example/x", c));
            expectFail(Reason.UNSUPPORTED, () -> ResolverRegistry.withDefaults().resolve("not a url", c));
        });
        test("built-ins win over manifest entries for the same host", () -> {
            String manifest = new String(Files.readAllBytes(Paths.get(args.length > 0 ? args[0] : "../manifest.json")), StandardCharsets.UTF_8);
            ResolverRegistry reg = ResolverRegistry.withDefaults().addManifest(manifest);
            eq("mediafire", reg.find(URI.create("https://www.mediafire.com/file/k/f/file")).name(), "mediafire builtin first");
            eq("gdrive-file", reg.find(URI.create("https://drive.google.com/file/d/" + ID + "/view")).name(), "gdrive builtin first");
        });

        System.out.println();
        System.out.println(passed + " passed, " + failures.size() + " failed");
        if (!failures.isEmpty()) {
            for (String f : failures) System.out.println("  - " + f);
            System.exit(1);
        }
    }
}
