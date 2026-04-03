package ai.core.api.server.session;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public enum EventType {
    @Property(name = "text_chunk")
    TEXT_CHUNK,
    @Property(name = "reasoning_chunk")
    REASONING_CHUNK,
    @Property(name = "reasoning_complete")
    REASONING_COMPLETE,
    @Property(name = "tool_start")
    TOOL_START,
    @Property(name = "tool_result")
    TOOL_RESULT,
    @Property(name = "tool_approval_request")
    TOOL_APPROVAL_REQUEST,
    @Property(name = "turn_complete")
    TURN_COMPLETE,
    @Property(name = "error")
    ERROR,
    @Property(name = "status_change")
    STATUS_CHANGE,
    @Property(name = "plan_update")
    PLAN_UPDATE
}
