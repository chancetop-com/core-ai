package ai.core.prompt;

/**
 * A structured prompt section that contributes to the system prompt.
 * Each implementation injects a distinct piece of the system prompt — identity, environment,
 * instructions, memory, hooks, etc. — which the Agent composes and renders together.
 */
@FunctionalInterface
public interface PromptInject {
    String inject();
}
