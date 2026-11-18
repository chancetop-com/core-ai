package ai.core.server.messaging;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgCommandRow {
    @Column(name = "id")
    public Long id;
    @Column(name = "type")
    public String type;
    @Column(name = "session_id")
    public String sessionId;
    @Column(name = "user_id")
    public String userId;
    @Column(name = "payload")
    public String payload;
    @Column(name = "request_id")
    public String requestId;
    @Column(name = "target_pod")
    public String targetPod;
    @Column(name = "attempts")
    public Integer attempts;
}
