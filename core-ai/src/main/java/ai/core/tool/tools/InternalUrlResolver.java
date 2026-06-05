package ai.core.tool.tools;

/**
 * @author stephen
 */
public interface InternalUrlResolver {
    String CONTEXT_KEY = "__internal_url_resolver";

    /**
     * Attempt to resolve a URL internally.
     *
     * @param url    the request URL
     * @param method the HTTP method (uppercase: GET, POST, …)
     * @return the resolved content, or {@code null} if this resolver cannot handle the URL
     */
    InternalUrlResult resolve(String url, String method);

    record InternalUrlResult(int statusCode, String contentType, byte[] body) {
    }
}
