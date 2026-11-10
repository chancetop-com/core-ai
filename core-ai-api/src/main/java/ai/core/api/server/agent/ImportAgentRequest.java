package ai.core.api.server.agent;

import core.framework.api.json.Property;
import core.framework.api.validate.NotBlank;

public class ImportAgentRequest {
    @NotBlank
    @Property(name = "content")
    public String content;   // raw exported envelope JSON

    @Property(name = "name")
    public String name;   // optional override for the imported agent name

    /**
     * Resolve the envelope without creating anything. The Agent editor uses this to preview what an
     * import would apply; the agent list page leaves it false and creates the draft.
     */
    @Property(name = "preview")
    public Boolean preview;
}
