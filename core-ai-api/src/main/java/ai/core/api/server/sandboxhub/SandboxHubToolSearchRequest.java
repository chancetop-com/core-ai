package ai.core.api.server.sandboxhub;

import core.framework.api.web.service.QueryParam;

/**
 * @author stephen
 */
public class SandboxHubToolSearchRequest {
    @QueryParam(name = "query")
    public String query;

    /** Optional kind filter: {@code mcp} / {@code api} / {@code llm_call} / {@code agent} / {@code builtin}. */
    @QueryParam(name = "kind")
    public String kind;

    @QueryParam(name = "limit")
    public Integer limit;
}
