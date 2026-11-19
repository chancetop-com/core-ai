package ai.core.server.report.store;

import ai.core.server.report.domain.ReportAuditEntry;
import core.framework.db.Database;
import core.framework.inject.Inject;

/**
 * @author core-ai
 */
public class PgReportAuditStore {
    @Inject
    Database database;

    public void append(ReportAuditEntry entry) {
        database.execute("INSERT INTO app.dataset_report_audits "
                        + "(id, user_id, report_id, action, actor_user_id, origin_session_id, origin_agent_id, revision, spec_hash, details_json, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)",
                entry.id, entry.userId, entry.reportId, entry.action, entry.actorUserId, entry.originSessionId,
                entry.originAgentId, entry.revision, entry.specHash, entry.detailsJson, entry.createdAt);
    }
}
