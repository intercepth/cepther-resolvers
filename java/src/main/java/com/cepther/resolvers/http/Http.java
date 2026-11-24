package com.cepther.resolvers.http;

import java.io.IOException;
import java.util.Map;

/**
 * The only networking the resolvers use. Implement it with whatever the app already has
 * (OkHttp on Android, Apache, ...); {@link JdkHttp} is the reference implementation.
 *
 * <p>Contract: follow redirects, keep cookies between calls, and <b>do not download file bodies</b> --
 * only read the body for textual types (html/json/xml) and leave {@code body == null} otherwise.
 */
public interface Http {

    HttpResponse get(String url, Map<String, String> headers) throws IOException;

    HttpResponse post(String url, Map<String, String> form, Map<String, String> headers) throws IOException;

    /** Cookie header value the client would send to {@code url}, or null. Used to hand cookies to the downloader. */
    default String cookieHeader(String url) {
        return null;
    }
}
