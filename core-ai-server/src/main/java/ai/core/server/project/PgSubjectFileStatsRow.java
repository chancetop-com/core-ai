package ai.core.server.project;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgSubjectFileStatsRow {
    @Column(name = "subject_id")
    public String subjectId;

    @Column(name = "total")
    public Long total;

    @Column(name = "latest")
    public Long latest;
}
