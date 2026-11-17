package ai.core.api.server.conversation;

import core.framework.api.json.Property;

/**
 * @author core-ai
 */
public class ConversationCancelRequest {
    @Property(name = "client_request_id")
    public String clientRequestId;

    @Property(name = "expected_run_id")
    public String expectedRunId;
}
