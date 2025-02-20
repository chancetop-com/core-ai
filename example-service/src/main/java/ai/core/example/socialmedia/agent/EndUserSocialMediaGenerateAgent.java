package ai.core.example.socialmedia.agent;

import ai.core.agent.Agent;
import ai.core.agent.formatter.formatters.DefaultJsonFormatter;
import ai.core.llm.LLMProvider;

/**
 * @author stephen
 */
public class EndUserSocialMediaGenerateAgent {
    public static Agent of(LLMProvider llmProvider) {
        return Agent.builder()
            .name("end-user-social-media-agent")
            .description("generate social media post for end users")
            .temperature(1.2D)
            .systemPrompt("""
                    Your are a skilled in write social media post and image suggestion assistant, you will write 5 posts for the user from a first-person perspective that match the following requirements:
                    1. Content Requirements:
                        - The post should be closely related to the user's idea and well-matched with the user uploaded image's description.
                        - The tone of the post should be lively and upbeat or cool.
                        - The post should contain at least five important keywords naturally incorporated into sentences.
                        - The post should be less than 3 sentences long.
                        - The image you describe should preferably be in a currently popular style.
                    2. Image Suggestion Requirements:
                        - Describe an image that you think would best complement the post content you have generated and output to image_suggestion.
                        - Turn your image description into multiple keywords suitable for image web searches and output to image_suggestion_keywords.
                        - The image_suggestion_keywords sort by the relevance to the post.
                        - The image_suggestion_keywords is a string and split by , - comma.
                        - The image_suggestion_keywords's contain less than 3 phrase and every phrase less than 3 words.
                    3. Formatting Requirements:
                        - Output as json format.
                        - output the post content to post_contents which are a list of the 5 posts.
                        - The output json contain 3 keys: post_contents, image_suggestion, image_suggestion_keywords.
                    """)
            .promptTemplate("User's location: {{location}}\nUser uploaded image's description: {{image_caption}}\nUser's idea: ")
            .formatter(new DefaultJsonFormatter())
            .llmProvider(llmProvider)
            .build();
    }
}
