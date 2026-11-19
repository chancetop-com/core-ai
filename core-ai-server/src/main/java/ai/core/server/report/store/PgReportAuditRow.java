package ai.core.server.report.store;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author core-ai
 */
public class PgReportAuditRow {
    @Column(name = "id")
    public String id;

    @Column(name = "user_id")
    public String userId;

    @Column(name = "report_id")
    public String reportId;

    @Column(name = "action")
    public String action;

    @Column(name = "actor_user_id")
    public String actorUserId;

    @Column(name = "origin_session_id")
    public String originSessionId;

    @Column(name = "origin_agent_id")
    public String originAgentId;

    @Column(name = "revision")
    public Long revision;

    @Column(name = "spec_hash")
    public String specHash;

    @Column(name = "details_json")
    public String detailsJson;

    @Column(name = "created_at")
    public ZonedDateTime createdAt;
}
