package ai.core.api.server.trace;

import core.framework.api.json.Property;

/**
 * JSON view mirror of the span status enum.
 *
 * @author stephen
 */
public enum SpanStatusView {
    @Property(name = "OK")
    OK,
    @Property(name = "CANCELLED")
    CANCELLED,
    @Property(name = "ERROR")
    ERROR
}
