package ai.core.api.server.evaluation.response;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListSpecsResponse {
    @Property(name = "specs")
    public List<EvalSpecView> specs;
}
