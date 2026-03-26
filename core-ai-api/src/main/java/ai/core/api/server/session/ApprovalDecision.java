package ai.core.api.server.session;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public enum ApprovalDecision {
    @Property(name = "APPROVE")
    APPROVE,
    @Property(name = "APPROVE_ALWAYS")
    APPROVE_ALWAYS,
    @Property(name = "APPROVE_SESSION")
    APPROVE_SESSION,
    @Property(name = "DENY")
    DENY,
    @Property(name = "DENY_ALWAYS")
    DENY_ALWAYS
}
