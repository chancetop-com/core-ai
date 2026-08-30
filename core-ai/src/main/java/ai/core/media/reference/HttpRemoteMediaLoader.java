package ai.core.media.reference;

import ai.core.internal.http.PatchedHTTPClientBuilder;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.http.HTTPResponse;

import java.net.URI;
import java.time.Duration;

/**
 * @author stephen
 */
public class HttpRemoteMediaLoader implements RemoteMediaLoader {
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;

    private final HTTPClient client = new PatchedHTTPClientBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .timeout(Duration.ofSeconds(30))
            .trustAll()
            .build();

    /**
     * Follows redirects manually: the shared HTTP client is built with followRedirects(false), while
     * this platform's own artifact/file URLs answer with a 307 to a short-lived pre-signed object
     * storage URL.
     */
    @Override
    public Loaded load(String url) {
        var target = url;
        for (var hop = 0; ; hop++) {
            var response = client.execute(new HTTPRequest(HTTPMethod.GET, target));
            if (response.statusCode < 300 || response.statusCode >= 400) {
                if (response.statusCode < 200 || response.statusCode >= 400) {
                    throw new IllegalArgumentException("failed to download reference image: HTTP " + response.statusCode);
                }
                if (response.body.length > MAX_BYTES) {
                    throw new IllegalArgumentException("reference image too large: " + response.body.length + " bytes (max " + MAX_BYTES + ")");
                }
                return new Loaded(response.body, header(response, "Content-Type"));
            }
            if (hop >= MAX_REDIRECTS) {
                throw new IllegalArgumentException("too many redirects while downloading reference image: " + url);
            }
            target = redirectTarget(target, header(response, "Location"), response.statusCode);
        }
    }

    private String redirectTarget(String from, String location, int statusCode) {
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("reference image redirect is missing a Location header: HTTP " + statusCode);
        }
        try {
            return URI.create(from).resolve(location).toString();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("reference image redirect has an invalid Location: " + location, e);
        }
    }

    // response headers preserve the origin's casing, so never match them case-sensitively
    private String header(HTTPResponse response, String name) {
        if (response.headers == null) return null;
        for (var entry : response.headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        }
        return null;
    }
}
