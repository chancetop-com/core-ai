package ai.core.example.service;

import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.llm.LLMProvider;
import ai.core.prompt.langfuse.LangfusePromptConfig;
import ai.core.prompt.langfuse.LangfusePromptProvider;
import ai.core.prompt.langfuse.LangfusePromptTemplate;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Example service demonstrating Langfuse prompt management integration
 *
 * This example shows three ways to use Langfuse prompts:
 * 1. Direct prompt fetching with LangfusePromptProvider
 * 2. Prompt template compilation with variables
 * 3. Agent builder integration with automatic prompt fetching
 *
 * @author stephen
 */
public class LangfusePromptExampleService {
    private static final Logger LOGGER = LoggerFactory.getLogger(LangfusePromptExampleService.class);

    @Inject
    LLMProvider llmProvider;

    private LangfusePromptProvider promptProvider;

    /**
     * Initialize Langfuse prompt provider
     * Call this method before using the service
     */
    public void initialize(String baseUrl, String publicKey, String secretKey) {
        // Create Langfuse configuration
        LangfusePromptConfig config = LangfusePromptConfig.builder()
            .baseUrl(baseUrl)  // e.g., "https://cloud.langfuse.com" or "https://us.cloud.langfuse.com"
            .credentials(publicKey, secretKey)  // Your Langfuse API keys
            .timeoutSeconds(10)
            .build();

        // Create prompt provider with caching enabled
        this.promptProvider = new LangfusePromptProvider(config, true);
    }

    /**
     * Example 1: Direct prompt fetching
     * Fetch a prompt directly from Langfuse by name
     */
    public String fetchPromptExample() {
        try {
            // Fetch the latest production version
            var prompt = promptProvider.getPrompt("movie-critic");
            LOGGER.info("Fetched prompt '{}' version {}: {}",
                prompt.getName(), prompt.getVersion(), prompt.getPromptContent());

            // Fetch a specific version
            var promptV2 = promptProvider.getPrompt("movie-critic", 2);
            LOGGER.info("Fetched prompt '{}' version {}",
                promptV2.getName(), promptV2.getVersion());

            // Fetch by label
            var stagingPrompt = promptProvider.getPromptByLabel("movie-critic", "staging");
            LOGGER.info("Fetched prompt '{}' with label 'staging'", stagingPrompt.getName());

            return prompt.getPromptContent();
        } catch (LangfusePromptProvider.LangfusePromptException e) {
            LOGGER.error("Failed to fetch prompt", e);
            throw new RuntimeException("Failed to fetch prompt from Langfuse", e);
        }
    }

    /**
     * Example 2: Prompt template compilation with variables
     * Fetch a prompt from Langfuse and compile it with variables using Mustache
     */
    public String compilePromptWithVariablesExample() {
        try {
            // Create a prompt template that uses Langfuse
            LangfusePromptTemplate promptTemplate = new LangfusePromptTemplate(promptProvider);

            // Define variables to substitute in the prompt
            Map<String, Object> variables = new HashMap<>();
            variables.put("criticLevel", "professional");
            variables.put("movie", "Inception");

            // Compile the prompt with variables
            // Assumes the Langfuse prompt contains: "As a {{criticLevel}} movie critic, rate {{movie}} out of 10."
            String compiledPrompt = promptTemplate.execute("movie-critic", variables, "movie-critic");
            LOGGER.info("Compiled prompt: {}", compiledPrompt);

            return compiledPrompt;
        } catch (Exception e) {
            LOGGER.error("Failed to compile prompt with variables", e);
            throw new RuntimeException("Failed to compile prompt", e);
        }
    }

    /**
     * Example 3: Agent builder integration
     * Create an agent that automatically fetches prompts from Langfuse
     */
    public Agent createAgentWithLangfusePromptsExample() {
        // Build an agent with Langfuse prompt integration
        Agent agent = Agent.builder()
            .name("movie-critic-agent")
            .description("An agent that critiques movies")
            .llmProvider(llmProvider)
            .langfuseSystemPrompt("movie-critic-system")
            // Specify which prompts to fetch from Langfuse
            .langfusePromptTemplate("movie-critic-template")  // Prompt template name in Langfuse
            // Optional: specify version or label
            .langfusePromptVersion(3)  // Use specific version
            // OR use label instead:
            // .langfusePromptLabel("production")

            .build();

        LOGGER.info("Created agent '{}' with Langfuse prompts", agent.getName());
        return agent;
    }

    /**
     * Example 4: Using agent with Langfuse prompts
     */
    public String runAgentWithLangfusePromptsExample(String userQuery) {
        // Create agent with Langfuse prompts
        Agent agent = createAgentWithLangfusePromptsExample();

        // Execute agent with user query
        String response = agent.run(userQuery, ExecutionContext.empty());
        LOGGER.info("Agent response: {}", response);

        return response;
    }

    /**
     * Example 5: Prompt caching and management
     */
    public void promptCachingExample() {
        try {
            // First fetch - goes to Langfuse API
            var prompt1 = promptProvider.getPrompt("movie-critic");
            LOGGER.info("First fetch: {}", prompt1.getName());

            // Second fetch - retrieved from cache
            var prompt2 = promptProvider.getPrompt("movie-critic");
            LOGGER.info("Second fetch (cached): {}", prompt2.getName());

            // Clear cache
            promptProvider.clearCache();
            LOGGER.info("Cache cleared");

            // Remove specific prompt from cache
            promptProvider.removeCachedPrompt("movie-critic", null, null);
            LOGGER.info("Removed specific prompt from cache");

        } catch (LangfusePromptProvider.LangfusePromptException e) {
            LOGGER.error("Caching example failed", e);
        }
    }

    /**
     * Example 6: Working with prompt config metadata
     */
    public void promptConfigExample() {
        try {
            var prompt = promptProvider.getPrompt("movie-critic");

            // Access prompt metadata
            LOGGER.info("Prompt ID: {}", prompt.getId());
            LOGGER.info("Prompt version: {}", prompt.getVersion());
            LOGGER.info("Prompt type: {}", prompt.getType());
            LOGGER.info("Created at: {}", prompt.getCreatedAt());
            LOGGER.info("Updated at: {}", prompt.getUpdatedAt());
            LOGGER.info("Labels: {}", prompt.getLabels());
            LOGGER.info("Tags: {}", prompt.getTags());

            // Access prompt config (e.g., model parameters)
            Map<String, Object> config = prompt.getConfig();
            if (config != null && !config.isEmpty()) {
                LOGGER.info("Prompt config: {}", config);
                // Example: get model from config
                String model = (String) config.get("model");
                Double temperature = (Double) config.get("temperature");
                LOGGER.info("Model: {}, Temperature: {}", model, temperature);
            }

        } catch (LangfusePromptProvider.LangfusePromptException e) {
            LOGGER.error("Config example failed", e);
        }
    }

    /**
     * Get the prompt provider (for testing or advanced usage)
     */
    public LangfusePromptProvider getPromptProvider() {
        return promptProvider;
    }
}
