package ai.core.api.server.trace;

import core.framework.api.json.Property;

/**
 * @author Xander
 */
public class StopTraceResponse {
    @Property(name = "trace_id")
    public String traceId;

    @Property(name = "status")
    public TraceStatusView status;

    // "session" (chat/test turn), "run" (api/scheduled/a2a run) or "none" (no live execution to signal)
    @Property(name = "target")
    public String target;

    // true when a cancel signal was delivered to a live execution; false when the trace was only marked cancelled
    @Property(name = "signalled")
    public Boolean signalled;
}
