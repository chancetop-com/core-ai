package ai.core.api.server.hub;

import core.framework.api.web.service.QueryParam;

/**
 * Query for {@code records.query}: conditions, projection, time window and paging. {@code filter} is the
 * same JSON object text the builtin tool accepts, so both surfaces match on exactly the same expressions.
 *
 * @author stephen
 */
public class HubDatasetRecordsQuery {
    @QueryParam(name = "filter")
    public String filter;

    @QueryParam(name = "fields")
    public String fields;

    @QueryParam(name = "from")
    public String from;

    @QueryParam(name = "to")
    public String to;

    @QueryParam(name = "limit")
    public Integer limit;

    @QueryParam(name = "offset")
    public Integer offset;
}
