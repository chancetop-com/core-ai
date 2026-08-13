package ai.core.api.server.trace;

import core.framework.api.json.Property;

/**
 * JSON view mirror of the trace status enum.
 *
 * @author stephen
 */
public enum TraceStatusView {
    @Property(name = "RUNNING")
    RUNNING,
    @Property(name = "COMPLETED")
    COMPLETED,
    @Property(name = "CANCELLED")
    CANCELLED,
    @Property(name = "ERROR")
    ERROR
}
