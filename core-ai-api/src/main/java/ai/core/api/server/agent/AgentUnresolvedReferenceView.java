package ai.core.api.server.agent;

import core.framework.api.json.Property;

/**
 * A reference in the imported file that does not resolve in the target environment. The import still
 * succeeds; the reference is dropped and the caller can fix it in the editor before publishing.
 */
public class AgentUnresolvedReferenceView {
    @Property(name = "ref_type")
    public String refType;   // SKILL | SUBAGENT

    @Property(name = "ref_id")
    public String refId;

    @Property(name = "ref_name")
    public String refName;

    @Property(name = "message")
    public String message;
}
