package ai.core.api.server.sandboxhub;

import core.framework.api.json.Property;

/**
 * Tool detail ({@code GET /api/sandbox-hub/tools/:name}): the summary plus what a caller needs to
 * invoke the tool.
 * <p>
 * {@code input_schema} is JSON text, not a nested bean: schemas are arbitrary dynamic objects and
 * core-ng view beans only allow concrete value types. The Go runtime and the thin CLI decode it as
 * a {@code string}, so it must never be serialized as an object.
 *
 * @author stephen
 */
public class SandboxHubToolDetail {
    @Property(name = "name")
    public String name;

    @Property(name = "kind")
    public String kind;

    @Property(name = "group")
    public String group;

    @Property(name = "path")
    public String path;

    @Property(name = "ref_id")
    public String refId;

    @Property(name = "description")
    public String description;

    @Property(name = "exposure")
    public String exposure;

    /** False for hidden/intercepted tools; the catalog already excludes them, this is the authoritative check. */
    @Property(name = "callable")
    public Boolean callable;

    /** Server-enforced default wait for this tool kind; the request may lower it, never exceed 600. */
    @Property(name = "timeout_seconds")
    public Integer timeoutSeconds;

    @Property(name = "input_schema")
    public String inputSchema;
}
