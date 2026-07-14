package ai.core.server.trace.service;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class TraceListFilter {
    public int offset;
    public int limit = 20;
    public String q;           // user-friendly search: ID fields, user account, trace name, or agent name
    public String name;        // advanced raw regex on name, evaluated after indexed filters
    public String type;        // agent | llm_call | external
    public String source;      // chat | a2a | api | scheduled | workflow
    public String agentName;
    public String model;
    public String status;
    public String sessionId;
    public String userId;
    public ZonedDateTime startFrom;
    public ZonedDateTime startTo;
}
