package ai.core.server.conversation;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author core-ai
 */
public class PgConversationRequestRow {
    @Column(name = "request_key")
    public String requestKey;

    @Column(name = "subject")
    public String subject;

    @Column(name = "kind")
    public String kind;

    @Column(name = "session_id")
    public String sessionId;

    @Column(name = "payload_hash")
    public String payloadHash;

    @Column(name = "status")
    public String status;

    @Column(name = "response_json")
    public String responseJson;

    @Column(name = "error_message")
    public String errorMessage;

    @Column(name = "claim_token")
    public String claimToken;

    @Column(name = "created_at")
    public ZonedDateTime createdAt;

    @Column(name = "expires_at")
    public ZonedDateTime expiresAt;
}
