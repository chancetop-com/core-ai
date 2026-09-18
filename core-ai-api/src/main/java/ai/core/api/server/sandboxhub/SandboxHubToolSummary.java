package ai.core.api.server.sandboxhub;

import core.framework.api.json.Property;

/**
 * One callable capability of the session's sandbox, as the runtime contract describes it
 * ({@code sdk/core-ai-session/contract-fixtures/catalog.json}). Field names are frozen: the Go runtime,
 * the thin CLI and the Python SDK all decode this shape.
 *
 * @author stephen
 */
public class SandboxHubToolSummary {
    /**
     * Script-facing name ({@code google_gbp_list_reviews}) — derived from the tool reference by
     * replacing every non-alphanumeric character with {@code _}.
     */
    @Property(name = "name")
    public String name;

    /** One of {@code mcp} / {@code api} / {@code llm_call} / {@code agent} / {@code builtin}. */
    @Property(name = "kind")
    public String kind;

    /** Owning MCP server or API app; empty for llm_call/agent/builtin. */
    @Property(name = "group")
    public String group;

    /** Slash path inside the group ({@code google-gbp/list_reviews}), or the plain name for singletons. */
    @Property(name = "path")
    public String path;

    @Property(name = "ref_id")
    public String refId;

    @Property(name = "description")
    public String description;

    /** {@code direct} or {@code hidden}; hidden tools never reach the sandbox. */
    @Property(name = "exposure")
    public String exposure;
}
