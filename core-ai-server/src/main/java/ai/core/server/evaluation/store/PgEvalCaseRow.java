package ai.core.server.evaluation.store;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgEvalCaseRow {
    @Column(name = "id")
    public String id;
    @Column(name = "dataset_revision_id")
    public String datasetRevisionId;
    @Column(name = "case_id")
    public String caseId;
    @Column(name = "case_hash")
    public String caseHash;
    @Column(name = "split")
    public String split;
    @Column(name = "weight")
    public Double weight;
    @Column(name = "body_json")
    public String bodyJson;
}
