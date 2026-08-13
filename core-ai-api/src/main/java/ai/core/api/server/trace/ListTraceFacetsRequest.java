package ai.core.api.server.trace;

import core.framework.api.web.service.QueryParam;

/**
 * Facet query reuses the trace filter context plus the facet field.
 *
 * @author stephen
 */
public class ListTraceFacetsRequest {
    @QueryParam(name = "field")
    public String field;

    @QueryParam(name = "q")
    public String q;

    @QueryParam(name = "name")
    public String name;

    @QueryParam(name = "type")
    public String type;

    @QueryParam(name = "source")
    public String source;

    @QueryParam(name = "agentName")
    public String agentName;

    @QueryParam(name = "model")
    public String model;

    @QueryParam(name = "status")
    public String status;

    @QueryParam(name = "sessionId")
    public String sessionId;

    @QueryParam(name = "userId")
    public String userId;

    @QueryParam(name = "range")
    public String range;

    @QueryParam(name = "startFrom")
    public String startFrom;

    @QueryParam(name = "startTo")
    public String startTo;
}
