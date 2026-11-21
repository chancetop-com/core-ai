package ai.core.api.server.evaluation.response;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListGraderSuitesResponse {
    @Property(name = "suites")
    public List<GraderSuiteView> suites;
}
