package ai.core.server.evaluation.domain;

/**
 * @author stephen
 */
public class EvalCaseEntity {
    public String id;
    public String datasetRevisionId;
    public String caseId;
    public String caseHash;
    public String split;
    public Double weight;
    public String bodyJson;
}
