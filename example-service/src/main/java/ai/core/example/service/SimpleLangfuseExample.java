package ai.core.example.service;

import ai.core.agent.Agent;
import ai.core.llm.LLMProvider;
import core.framework.inject.Inject;

import java.util.HashMap;
import java.util.Map;

/**
 * Simplified example showing how easy it is to use Langfuse prompts
 * with automatic configuration from properties
 *
 * Configuration needed in conf/sys.properties:
 * langfuse.prompt.base.url=https://cloud.langfuse.com
 * langfuse.prompt.public.key=pk-lf-...
 * langfuse.prompt.secret.key=sk-lf-...
 *
 * @author stephen
 */
public class SimpleLangfuseExample {

    @Inject
    LLMProvider llmProvider;

    /**
     * Example 1: Simplest usage - just specify prompt names
     * The connection is automatically configured from properties
     */
    public Agent createSimpleAgent() {
        return Agent.builder()
            .name("movie-critic")
            .llmProvider(llmProvider)

            // That's it! Just specify the prompt names from Langfuse
            .langfuseSystemPrompt("movie-critic-system")
            .langfusePromptTemplate("movie-critic-template")

            .build();
    }

    /**
     * Example 2: Using a specific version
     */
    public Agent createAgentWithVersion() {
        return Agent.builder()
            .name("movie-critic-v2")
            .llmProvider(llmProvider)
            .langfuseSystemPrompt("movie-critic-system")
            .langfusePromptVersion(2)  // Use version 2
            .build();
    }

    /**
     * Example 3: Using a label (e.g., production, staging)
     */
    public Agent createAgentWithLabel() {
        return Agent.builder()
            .name("movie-critic-prod")
            .llmProvider(llmProvider)
            .langfuseSystemPrompt("movie-critic-system")
            .langfusePromptLabel("production")  // Use production label
            .build();
    }

    /**
     * Example 4: Run agent with variable substitution
     */
    public String rateMovie(String movie, String criticLevel) {
        Agent agent = createSimpleAgent();

        // Pass variables for Mustache template
        Map<String, Object> variables = new HashMap<>();
        variables.put("movie", movie);
        variables.put("criticLevel", criticLevel);

        return agent.run("Rate this movie", variables);
    }

    /**
     * Example 5: Combined with other agent features
     */
    public Agent createFullFeaturedAgent() {
        return Agent.builder()
            .name("advanced-agent")
            .description("A full-featured agent with Langfuse prompts")
            .llmProvider(llmProvider)

            // Langfuse prompts
            .langfuseSystemPrompt("advanced-system")
            .langfusePromptTemplate("advanced-template")
            .langfusePromptLabel("production")

            // Other agent features
            .temperature(0.7)
            .model("gpt-4")
            .useGroupContext(false)

            .build();
    }
}
