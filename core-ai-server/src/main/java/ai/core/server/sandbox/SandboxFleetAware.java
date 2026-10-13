package ai.core.server.sandbox;

/**
 * Implemented by providers that can report the cluster-wide sandbox fleet (Kubernetes-backed ones).
 * Kept separate from {@code SandboxProvider} so the core abstraction — and the CLI's local runtime —
 * does not have to know about an orchestration surface only the server has.
 *
 * @author stephen
 */
public interface SandboxFleetAware {
    /** Live counts and ages; implementations return what they could read rather than throwing. */
    SandboxFleet providerFleet();
}
