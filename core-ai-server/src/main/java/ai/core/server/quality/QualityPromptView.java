package ai.core.server.quality;

/**
 * Effective instructions of a candidate. {@code systemTimeInjected} is the result of rendering the actual
 * template with a sentinel {@code system.time}, not a substring search: a variable that is absent from the
 * template's scope, inside an unused block, or merely quoted does not count as injection.
 *
 * @author core-ai
 */
public record QualityPromptView(String systemPrompt,
                                String inputTemplate,
                                String source,
                                String systemPromptId,
                                boolean managedPrompt,
                                boolean systemTimeInjected,
                                String hash) {
}
