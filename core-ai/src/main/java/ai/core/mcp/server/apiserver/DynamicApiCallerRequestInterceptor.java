package ai.core.mcp.server.apiserver;

import core.framework.http.HTTPRequest;

/**
 * @author stephen
 */
public interface DynamicApiCallerRequestInterceptor {
    HTTPRequest invoke(HTTPRequest request);
}
