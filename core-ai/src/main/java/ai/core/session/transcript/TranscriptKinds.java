package ai.core.session.transcript;

/**
 * Record kind names. They match the live event type names wherever an event exists.
 *
 * @author stephen
 */
public final class TranscriptKinds {
    public static final String SESSION_START = "session_start";
    public static final String TURN_START = "turn_start";
    public static final String STEER = "steer";
    public static final String ASSISTANT_TEXT = "assistant_text";
    public static final String REASONING = "reasoning";
    public static final String TOOL_START = "tool_start";
    public static final String TOOL_RESULT = "tool_result";
    public static final String TOOL_APPROVAL = "tool_approval";
    public static final String BATCH_TOOL_START = "batch_tool_start";
    public static final String PLAN_UPDATE = "plan_update";
    public static final String COMPRESSION = "compression";
    public static final String SANDBOX = "sandbox";
    public static final String TASK_STATUS = "task_status";
    public static final String CUSTOM = "custom";
    public static final String TURN_COMPLETE = "turn_complete";
    public static final String ERROR = "error";
    public static final String NOTICE = "notice";
    public static final String REWIND = "rewind";

    public static final String SOURCE_USER = "user";
    public static final String SOURCE_TASK_NOTE = "task_note";

    public static final String STEER_PENDING = "pending";
    public static final String STEER_COMMITTED = "committed";
    public static final String STEER_CANCELLED = "cancelled";

    private TranscriptKinds() {
    }
}
