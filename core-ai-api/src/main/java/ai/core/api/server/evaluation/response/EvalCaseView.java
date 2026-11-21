package ai.core.api.server.evaluation.response;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class EvalCaseView {
    @Property(name = "case_id")
    public String caseId;

    @Property(name = "split")
    public String split;

    @Property(name = "weight")
    public Double weight;

    @Property(name = "input_text")
    public String inputText;

    @Property(name = "grader_refs_json")
    public String graderRefsJson;

    @Property(name = "tags_json")
    public String tagsJson;

    @Property(name = "environment_json")
    public String environmentJson;

    @Property(name = "reference_json")
    public String referenceJson;
}
