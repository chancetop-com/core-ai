package ai.core.api.server.conversation;

import core.framework.api.json.Property;

/**
 * Transport capabilities a direct-conversation client may rely on. Feature flags describe the
 * server, not authorization: every command is still checked against the caller and the session.
 *
 * @author core-ai
 */
public class ConversationCapabilitiesResponse {
    @Property(name = "protocol_version")
    public Integer protocolVersion;

    @Property(name = "subject")
    public String subject;

    @Property(name = "subject_type")
    public String subjectType;

    @Property(name = "direct_conversation")
    public Boolean directConversation;

    @Property(name = "idempotent_commands")
    public Boolean idempotentCommands;

    @Property(name = "history_paging")
    public Boolean historyPaging;

    @Property(name = "attachment_upload")
    public Boolean attachmentUpload;

    @Property(name = "tool_approval")
    public Boolean toolApproval;

    @Property(name = "event_stream")
    public String eventStream;

    @Property(name = "max_agents")
    public Integer maxAgents;

    @Property(name = "max_history_messages")
    public Integer maxHistoryMessages;
}
