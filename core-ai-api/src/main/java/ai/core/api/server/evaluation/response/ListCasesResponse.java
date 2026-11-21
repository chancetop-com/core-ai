package ai.core.api.server.evaluation.response;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListCasesResponse {
    @Property(name = "cases")
    public List<EvalCaseView> cases;

    @Property(name = "total")
    public Long total;
}
