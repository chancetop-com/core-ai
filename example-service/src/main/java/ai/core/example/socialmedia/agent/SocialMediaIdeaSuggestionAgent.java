package ai.core.example.socialmedia.agent;

import ai.core.agent.Agent;
import ai.core.rag.RagConfig;
import ai.core.llm.LLMProvider;
import ai.core.rag.VectorStore;

/**
 * @author stephen
 */
public class SocialMediaIdeaSuggestionAgent {
    public static Agent of(LLMProvider llmProvider, VectorStore vectorStore) {
        return Agent.builder()
                .name("social-media-idea-suggestion-agent")
                .description("generate idea of wonder ops' social media for suggestion")
                .systemPrompt("Your are a assistant that help user's query.")
                .promptTemplate("""
                        I am a new media operations officer for a restaurant, and I need you to recommend 3-5 ideas for me so that I can create content based on them.
                        Each idea should be a phrase keyword of no more than 5 words.
                        These ideas should conform to the current popular trends.
                        Output only contain the ideas, and split with ',' - comma.
                        and do not suggestion that is already used this week and if the list is empty, ignore that, here is the list:
                        {{used_suggestion_list}}
                        """)
                .llmProvider(llmProvider)
                .temperature(1.4d)
                .ragConfig(RagConfig.builder()
                        .collection("wiki")
                        .vectorStore(vectorStore).build()).build();
    }
}
