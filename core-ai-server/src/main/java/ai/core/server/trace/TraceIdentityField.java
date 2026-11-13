package ai.core.server.trace;

/**
 * Trace attribution columns the root span can backfill after a child span created the document.
 *
 * @author stephen
 */
public enum TraceIdentityField {
    USER_ID, SESSION_ID, AGENT_ID, AGENT_NAME
}
