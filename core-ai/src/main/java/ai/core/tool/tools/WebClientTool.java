package ai.core.tool.tools;

import core.framework.http.ContentType;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPHeaders;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.inject.Inject;

import java.nio.charset.Charset;
import java.util.Locale;

/**
 * @author stephen
 */
public class WebClientTool {
    private static final String BROWSER_DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0";

    @Inject
    HTTPClient client;

    public String call(String url, String method, String contentType, String body) {
        var request = new HTTPRequest(HTTPMethod.valueOf(method.toLowerCase(Locale.ROOT)), url);
        request.headers.put(HTTPHeaders.USER_AGENT, BROWSER_DEFAULT_USER_AGENT);
        request.headers.put(HTTPHeaders.CONTENT_TYPE, contentType);
        if (body != null) {
            request.body(body.getBytes(Charset.defaultCharset()), ContentType.APPLICATION_JSON);
        }
        return client.execute(request).text();
    }
}
