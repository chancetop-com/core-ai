package ai.core.api.server.report;

import core.framework.api.web.service.QueryParam;

/**
 * @author core-ai
 */
public class DeletePinRequest {
    @QueryParam(name = "expected_layout_revision")
    public Long expectedLayoutRevision;
}
