package ai.core.api.server.quality;

import core.framework.api.web.service.QueryParam;

/**
 * @author core-ai
 */
public class QualityExemptionSubjectListRequest {
    /** {@code AGENT} or {@code SKILL}. */
    @QueryParam(name = "type")
    public String type;

    @QueryParam(name = "query")
    public String query;
}
