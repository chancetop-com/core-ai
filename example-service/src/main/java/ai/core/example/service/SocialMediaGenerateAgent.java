package ai.core.example.service;

import ai.core.agent.Agent;
import ai.core.rag.RagConfig;
import ai.core.agent.formatter.formatters.DefaultJsonFormatter;
import ai.core.llm.LLMProvider;
import ai.core.rag.VectorStore;
import core.framework.api.json.Property;
import core.framework.json.JSON;
import core.framework.util.Maps;

import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class SocialMediaGenerateAgent {
    private final Agent agent;

    public SocialMediaGenerateAgent(LLMProvider llmProvider, VectorStore vectorStore) {
        this.agent = Agent.builder().name("social-media-agent").description("An agent that help generate social media posts with knowledge base.")
                .systemPrompt("""
                        Based on the *idea*, *restaurant location* and *Business Description* I provide, you will create 5 social media post and image suggestion that match the following requirements:
                        1. Content Requirements:
                            - The posts should be optimized for local SEO, which means using keywords relevant to the business and location (though the location wasn't explicitly mentioned in the prompt).
                            - Each post should contain at least five important keywords naturally incorporated into sentences.
                            - Each post should be at least 3 sentences long.
                            - No need to generate images, only generate image suggestion, image suggestion must less then 2 sentences long.
                            - Image suggestion without location information, and should not have a very complex composition.
                            - Begin with a bold header mentioning the business name.
                            - End with a request for feedback.
                            - If the idea is empty, open your mind.
                            - If the restaurant location, treat as Wonder itself.
                        2. Formatting Requirements
                            - Output as json format.
                            - output the post content to post_contents which are a list of the 5 posts text.
                            - The output json contain 2 keys: post_contents, image_suggestion.
                        Here is a example of output json:
                        {"post_contents": ["post1", "post2", "post n"], "image_suggestion": "suggestion"}
                        Business Description:
                        I am a media operations officer for one of the restaurant locations under Wonder. I need to create social media posts to drive traffic, encouraging people to dine in or place orders through the app named Wonder. Ensure that the values of the posts align with Wonder's mission.
                        Below is Wonder's mission:
                        ```
                        We’re on a mission to make great food more accessible
                        We started Wonder with the belief that a remarkable dining experience should be readily available to anyone who wants one.
                        Every corner of this country is searching for something better for breakfast, lunch, and dinner. So, we went on a nationwide search, looking for the best chefs and restaurants we could find.
                        However you want to eat, whatever you’re craving, we’re committed to bringing it to you with uncommon care, from your first tap on the app to our unparalleled customer service.
                        Wonder is going to be the premiere destination for mealtime—so we hope you’re hungry.
                        FOOD SOURCING
                         We are dedicated to becoming a leader in responsible and transparent food sourcing. We plan to:
                          Use seasonal and organic ingredients whenever possible.
                          Purchase food grown without synthetic fertilizers, pesticides, and/or using regenerative practices.
                          Source meat and poultry that is humanely raised and processed, without added antibiotics or hormones.
                          Work with leaders in the sustainable farming and aquaculture practices.
                        Healthy Buildings
                         Our goal is to leverage our real estate to make a positive environmental impact. Our initiatives include:
                          Pursuing zero waste at all our customer locations.
                          Maintaining LEED certification of our Central Kitchen.
                          Measuring and reducing our full value-agentChain emissions impact.
                        Food waste
                         We are committed to reducing food waste by:
                          Donating excess food to feed those facing food insecurity in our communities.
                          Assessing our waste streams to evaluate future upcycle opportunities.
                          Achieving our goal of zero food waste to landfill.
                        Packaging
                         Our elevated home dining requires high quality, revolutionary packaging. Our packaging goals center on:
                          Increasing our use of recyclable and compostable materials.
                          Working to eliminate excess packaging wherever possible.
                        ```
                        """)
                .promptTemplate("""
                        My restaurant location:
                        {{location}}
                        Idea for today's generation:
                        """).llmProvider(llmProvider).formatter(new DefaultJsonFormatter())
                .ragConfig(RagConfig.builder().useRag(Boolean.TRUE).threshold(0.8).collection("wiki").vectorStore(vectorStore).build()).build();
    }

    public SocialMediaPostDTO run(String query, String location) {
        Map<String, Object> map = Maps.newConcurrentHashMap();
        map.put("location", location);
        var text = agent.run(query, map);
        return JSON.fromJSON(SocialMediaPostDTO.class, text);
    }

    public static class SocialMediaPostDTO {
        @Property(name = "post_contents")
        public List<String> postContents;
        @Property(name = "post_contents_cn")
        public List<String> postContentsCn;
        @Property(name = "post_content")
        public String postContent;
        @Property(name = "image_suggestion")
        public String imageSuggestion;
        @Property(name = "image_suggestion_keywords")
        public String imageSuggestionKeywords;
    }
}
