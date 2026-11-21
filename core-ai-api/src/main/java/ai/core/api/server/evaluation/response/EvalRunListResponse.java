package ai.core.api.server.evaluation.response;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class EvalRunListResponse {
    @Property(name = "runs")
    public List<RunView> runs;

    @Property(name = "total")
    public Long total;
}
