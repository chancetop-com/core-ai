package ai.core.api.server.agenthub;

import core.framework.api.web.service.QueryParam;

/**
 * Search over the Agent Hub catalog. A blank {@code query} lists visible agents ordered by
 * {@code published_at} descending with {@code system_default} first ({@code limit} default
 * 20, max 200). {@code type} filters AGENT/LLM_CALL, {@code source} server/external.
 *
 * @author stephen
 */
public class AgentHubSearchRequest {
    @QueryParam(name = "query")
    public String query;

    @QueryParam(name = "type")
    public String type;

    @QueryParam(name = "source")
    public String source;

    @QueryParam(name = "limit")
    public Integer limit;
}
