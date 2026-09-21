package ai.core.server.sandbox.agentsandbox;

/**
 * How an agent sandbox was provisioned: a warm-pool {@code SandboxClaim}, or a directly created
 * {@code Sandbox} CR.
 *
 * <p>The mode is decided per acquisition — custom images and env vars cannot ride a claim — while the
 * provider itself is configured once. Every per-sandbox operation (status, renew, release, attach)
 * must therefore dispatch on the sandbox's own kind, never on the provider configuration.
 *
 * @author stephen
 */
public enum AgentSandboxKind {
    CLAIM,
    DIRECT;

    private static final String CLAIM_ID_PREFIX = "claim-";

    /**
     * Sandbox ids outlive the process (session bindings, session snapshots), so the provisioning mode
     * has to be recoverable from the id: claims are named {@code claim-<uuid>} at creation, direct
     * sandboxes {@code core-ai-sandbox-<uuid>}.
     */
    public static AgentSandboxKind fromId(String sandboxId) {
        return sandboxId != null && sandboxId.startsWith(CLAIM_ID_PREFIX) ? CLAIM : DIRECT;
    }

    public AgentSandboxKind other() {
        return this == CLAIM ? DIRECT : CLAIM;
    }
}
