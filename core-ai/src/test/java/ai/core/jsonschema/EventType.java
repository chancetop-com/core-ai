package ai.core.jsonschema;

import core.framework.api.json.Property;

/**
 * SSE Event Types for Menu Agent Streaming
 *
 * @author cyril
 */
public enum EventType {
    @Property(name = "start")
    START,

    @Property(name = "agent_response")
    AGENT_RESPONSE,

    @Property(name = "quick_replies")
    QUICK_REPLIES,

    @Property(name = "menu_table")
    MENU_TABLE,

    @Property(name = "menu_preview")
    MENU_PREVIEW,

    @Property(name = "menu_download")
    MENU_DOWNLOAD,

    @Property(name = "tool_call")
    TOOL_CALL,

    @Property(name = "tool_result")
    TOOL_RESULT,

    @Property(name = "completion")
    COMPLETION,

    @Property(name = "end")
    END,

    @Property(name = "error")
    ERROR,

    @Property(name = "heartbeat")
    HEARTBEAT,

    @Property(name = "state_update")
    STATE_UPDATE,

    // Evaluation events
    @Property(name = "evaluation_start")
    EVALUATION_START,

    @Property(name = "item_evaluation")
    ITEM_EVALUATION,

    @Property(name = "overall_summary")
    OVERALL_SUMMARY,

    @Property(name = "evaluation_summary_chunk")
    EVALUATION_SUMMARY_CHUNK,

    @Property(name = "evaluation_complete")
    EVALUATION_COMPLETE,

    // Price Recommendations events
    @Property(name = "price_recommendations")
    PRICE_RECOMMENDATIONS,

    @Property(name = "menu_performance_data")
    MENU_PERFORMANCE_ANALYSIS
}
