package ai.core.prompt.langfuse;

import ai.core.prompt.PromptTemplate;
import ai.core.prompt.engines.MustachePromptTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Prompt template that fetches prompts from Langfuse and compiles them with variables
 * Integrates Langfuse prompt management with Mustache templating
 *
 * @author stephen
 */
public class LangfusePromptTemplate implements PromptTemplate {
    private static final Logger LOGGER = LoggerFactory.getLogger(LangfusePromptTemplate.class);

    /**
     * Static utility method to compile a Langfuse prompt with variables
     *
     * @param provider   Langfuse prompt provider
     * @param promptName Prompt name in Langfuse
     * @param scopes     Variables to substitute
     * @return Compiled prompt string
     */
    public static String compile(LangfusePromptProvider provider, String promptName, Map<String, Object> scopes) {
        return new LangfusePromptTemplate(provider).execute(promptName, scopes, promptName);
    }

    /**
     * Static utility method to compile a Langfuse prompt with version
     *
     * @param provider   Langfuse prompt provider
     * @param promptName Prompt name in Langfuse
     * @param version    Prompt version
     * @param scopes     Variables to substitute
     * @return Compiled prompt string
     */
    public static String compile(LangfusePromptProvider provider, String promptName, Integer version, Map<String, Object> scopes) {
        return new LangfusePromptTemplate(provider).execute(promptName, version, scopes, promptName);
    }

    private final LangfusePromptProvider provider;
    private final PromptTemplate templateEngine;

    /**
     * Create a new LangfusePromptTemplate with default Mustache engine
     */
    public LangfusePromptTemplate(LangfusePromptProvider provider) {
        this(provider, new MustachePromptTemplate());
    }

    /**
     * Create a new LangfusePromptTemplate with custom template engine
     */
    public LangfusePromptTemplate(LangfusePromptProvider provider, PromptTemplate templateEngine) {
        this.provider = provider;
        this.templateEngine = templateEngine;
    }

    /**
     * Fetch a prompt from Langfuse and compile it with variables
     *
     * @param promptName The name of the prompt in Langfuse
     * @param scopes     Variables to substitute in the prompt template
     * @param name       Template name for compilation (usually the prompt name)
     * @return Compiled prompt string
     */
    @Override
    public String execute(String promptName, Map<String, Object> scopes, String name) {
        try {
            // Fetch prompt from Langfuse
            LangfusePrompt prompt = provider.getPrompt(promptName);

            // Get prompt content
            String template = prompt.getPromptContent();

            // Compile template with variables
            return templateEngine.execute(template, scopes, name != null ? name : promptName);
        } catch (LangfusePromptProvider.LangfusePromptException e) {
            LOGGER.error("Failed to fetch prompt '{}' from Langfuse", promptName, e);
            throw new RuntimeException("Failed to fetch prompt from Langfuse: " + promptName, e);
        }
    }

    /**
     * Fetch a prompt by version and compile it with variables
     *
     * @param promptName The name of the prompt in Langfuse
     * @param version    The version of the prompt
     * @param scopes     Variables to substitute in the prompt template
     * @param name       Template name for compilation
     * @return Compiled prompt string
     */
    public String execute(String promptName, Integer version, Map<String, Object> scopes, String name) {
        try {
            LangfusePrompt prompt = provider.getPrompt(promptName, version);
            String template = prompt.getPromptContent();
            return templateEngine.execute(template, scopes, name != null ? name : promptName);
        } catch (LangfusePromptProvider.LangfusePromptException e) {
            LOGGER.error("Failed to fetch prompt '{}' version {} from Langfuse", promptName, version, e);
            throw new RuntimeException("Failed to fetch prompt from Langfuse: " + promptName + " v" + version, e);
        }
    }

    /**
     * Fetch a prompt by label and compile it with variables
     *
     * @param promptName The name of the prompt in Langfuse
     * @param label      The label of the prompt (e.g., "production", "staging")
     * @param scopes     Variables to substitute in the prompt template
     * @param name       Template name for compilation
     * @return Compiled prompt string
     */
    public String executeByLabel(String promptName, String label, Map<String, Object> scopes, String name) {
        try {
            LangfusePrompt prompt = provider.getPromptByLabel(promptName, label);
            String template = prompt.getPromptContent();
            return templateEngine.execute(template, scopes, name != null ? name : promptName);
        } catch (LangfusePromptProvider.LangfusePromptException e) {
            LOGGER.error("Failed to fetch prompt '{}' with label '{}' from Langfuse", promptName, label, e);
            throw new RuntimeException("Failed to fetch prompt from Langfuse: " + promptName + " (" + label + ")", e);
        }
    }

    /**
     * Get the underlying Langfuse prompt provider
     */
    public LangfusePromptProvider getProvider() {
        return provider;
    }
}
