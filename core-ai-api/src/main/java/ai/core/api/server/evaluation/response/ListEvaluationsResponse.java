package ai.core.api.server.evaluation.response;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListEvaluationsResponse {
    @Property(name = "evaluations")
    public List<EvaluationView> evaluations;

    @Property(name = "total")
    public Long total;
}
