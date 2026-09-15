package ai.core.server.sandboxhub;

import core.framework.api.json.Property;

/**
 * RPC payload of a sandbox tool call. The sandbox identity belongs to the caller that holds the token
 * and is not part of the session on the owning pod, so it travels with the call to land in the audit
 * row of the executions the script triggers.
 *
 * @author stephen
 */
public class SandboxHubCallCommandPayload {
    @Property(name = "name")
    public String name;

    @Property(name = "arguments")
    public String arguments;

    @Property(name = "timeoutSeconds")
    public Integer timeoutSeconds;

    @Property(name = "sandboxId")
    public String sandboxId;
}
