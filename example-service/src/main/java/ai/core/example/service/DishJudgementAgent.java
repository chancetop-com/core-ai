package ai.core.example.service;

import ai.core.agent.Agent;
import ai.core.agent.formatter.formatters.DefaultJsonFormatter;
import ai.core.llm.LLMProvider;
import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class DishJudgementAgent {
    public static Agent of(LLMProvider llmProvider) {
        return Agent.builder().systemPrompt("")
                .promptTemplate("""
                                Does the following text is a name of dish or describe a dish? Return json result as follow:
                                If it is a name of a dish, please return the name of the dish in the dish_name and true in the judgement.
                                If it describe a dish, please return the name of the dish in the dish_name and true in the judgement.
                                If nothing about dish, please return false in the judgement.
                                Here is an example of return:
                                {"judgement": true, "dish_name": "xxx"}
                                
                                text:
                                """)
                .formatter(new DefaultJsonFormatter())
                .llmProvider(llmProvider).build();
    }

    public static class DishJudgementResult {
        @Property(name = "judgement")
        public Boolean judgement;

        @Property(name = "dish_name")
        public String dishName;
    }
}
