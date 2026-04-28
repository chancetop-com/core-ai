package ai.core.server.messaging;

/**
 * @author stephen
 */
public enum CommandType {
    SEND_MESSAGE,
    APPROVE_TOOL,
    CANCEL_TURN,
    CLOSE_SESSION,
    LOAD_TOOLS,
    LOAD_SKILLS,
    UNLOAD_SKILLS,
    LOAD_SUB_AGENTS,
    GENERATE_AGENT_DRAFT
}
