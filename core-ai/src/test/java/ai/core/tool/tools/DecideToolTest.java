package ai.core.tool.tools;

import ai.core.agent.ExecutionContext;
import ai.core.decision.DecisionAnswer;
import ai.core.decision.DecisionRequest;
import ai.core.decision.DecisionResponse;
import ai.core.decision.DecisionType;
import ai.core.decision.DecisionUsage;
import ai.core.utils.JsonUtil;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class DecideToolTest {
    @Test
    @SuppressWarnings("unchecked")
    void parsesQuestionsAndRendersStructuredAnswers() {
        var captured = new AtomicReference<DecisionRequest>();
        var context = ExecutionContext.builder().build();
        context.setDecisionProvider(request -> {
            captured.set(request);
            var answers = new LinkedHashMap<String, DecisionAnswer>();
            answers.put("team", new DecisionAnswer("team", DecisionType.CHOICE, 0.99, "engineering",
                    null, Map.of("billing", 0.01, "engineering", 0.99), 0.98, null));
            answers.put("urgent", new DecisionAnswer("urgent", DecisionType.NOUL, 0.12, null,
                    null, null, null, null));
            answers.put("severity", new DecisionAnswer("severity", DecisionType.SCORE, null, null,
                    2.9, Map.of("0", 0.01, "3", 0.99), 0.97, Map.of("0", "Low", "3", "Critical")));
            return new DecisionResponse(answers, "microsoft-decision-1", new DecisionUsage(10, 2));
        });

        var result = DecideTool.builder().build().execute("""
                {"state":"checkout is down",
                 "questions":[
                   {"name":"team","type":"choice","instructions":"Which team?","criteria":{"billing":"Billing","engineering":"Engineering"}},
                   {"name":"urgent","type":"noul","instructions":"Is it urgent?"},
                   {"name":"severity","type":"score","instructions":"How severe?","criteria":["Low","Medium","High","Critical"]}
                 ]}""", context);

        assertTrue(result.isCompleted(), result.getResult());
        var request = captured.get();
        assertEquals("checkout is down", request.state());
        assertEquals(3, request.questions().size());
        assertEquals(List.of("Low", "Medium", "High", "Critical"), request.questions().get(2).levels());
        var body = JsonUtil.toMap(result.getResult());
        assertEquals("microsoft-decision-1", body.get("model"));
        var answers = (Map<String, Object>) body.get("answers");
        var team = (Map<String, Object>) answers.get("team");
        assertEquals("engineering", team.get("choice"));
        assertEquals(0.99, team.get("probabilities") instanceof Map ? ((Map<String, Object>) team.get("probabilities")).get("engineering") : null);
        assertEquals(0.12, ((Map<String, Object>) answers.get("urgent")).get("noul"));
        assertEquals(2.9, ((Map<String, Object>) answers.get("severity")).get("score"));
    }

    @Test
    void rejectsUnknownQuestionType() {
        var context = ExecutionContext.builder().build();
        context.setDecisionProvider(request -> {
            throw new AssertionError("provider must not be called");
        });

        var result = DecideTool.builder().build().execute("""
                {"state":"x","questions":[{"name":"q","type":"bogus","instructions":"?"}]}""", context);

        assertTrue(result.isFailed());
        assertTrue(result.getResult().contains("unsupported question type"));
    }

    @Test
    void failsWhenNoProviderIsConfigured() {
        var result = DecideTool.builder().build().execute("""
                {"state":"x","questions":[{"name":"q","type":"noul","instructions":"?"}]}""",
                ExecutionContext.builder().build());

        assertTrue(result.isFailed());
        assertEquals("no decision provider configured", result.getResult());
    }

    @Test
    void acceptsQuestionsKeyedByName() {
        var captured = new AtomicReference<DecisionRequest>();
        var context = ExecutionContext.builder().build();
        context.setDecisionProvider(request -> {
            captured.set(request);
            return new DecisionResponse(Map.of(), "m", null);
        });

        var result = DecideTool.builder().build().execute("""
                {"state":"x","questions":{"q":{"type":"noul","instructions":"?"}}}""", context);

        assertFalse(result.isFailed(), result.getResult());
        assertEquals("q", captured.get().questions().getFirst().name());
    }

    @Test
    void descriptionListsAvailableModels() {
        var description = DecideTool.buildDescription(List.of(
                new ai.core.decision.DecisionModelHint("decision-1", "Decision 1", "azure-uat", "Decision-1"),
                new ai.core.decision.DecisionModelHint("jev", null, "typesafe", "jev-latest")));

        assertTrue(description.contains("- decision-1"));
        assertTrue(description.contains("azure-uat"));
        assertTrue(description.contains("- jev"));
    }
}
