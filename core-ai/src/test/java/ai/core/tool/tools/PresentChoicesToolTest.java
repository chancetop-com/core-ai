package ai.core.tool.tools;

import ai.core.agent.ExecutionContext;
import ai.core.api.server.session.AgentEvent;
import ai.core.api.server.session.CustomEvent;
import ai.core.session.CustomEventEmitter;
import ai.core.session.SessionCustomEventEmitter;
import core.framework.json.JSON;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class PresentChoicesToolTest {
    private final List<AgentEvent> dispatched = new ArrayList<>();
    private final PresentChoicesTool tool = PresentChoicesTool.builder().build();
    private final ExecutionContext context = ExecutionContext.builder()
        .customVariable(CustomEventEmitter.CONTEXT_KEY, new SessionCustomEventEmitter("s-1", dispatched::add, () -> true))
        .build();

    @Test
    @SuppressWarnings("unchecked")
    void presentsQuickRepliesWithValueFallingBackToLabel() {
        var result = tool.execute(JSON.toJSON(Map.of(
            "question", "Continue?",
            "options", List.of(
                Map.of("label", "Yes"),
                Map.of("label", "No", "value", "no thanks", "description", "skip this step")
            )
        )), context);

        assertFalse(result.isFailed());
        var event = (CustomEvent) dispatched.getFirst();
        assertEquals("quick_replies", event.name);
        var payload = JSON.fromJSON(Map.class, event.data);
        assertEquals("Continue?", payload.get("question"));
        var options = (List<Map<String, Object>>) payload.get("options");
        assertEquals(2, options.size());
        assertEquals("Yes", options.getFirst().get("label"));
        assertEquals("Yes", options.getFirst().get("value"));
        assertEquals("no thanks", options.get(1).get("value"));
        assertEquals("skip this step", options.get(1).get("description"));
    }

    @Test
    void omitsTheQuestionWhenAbsent() {
        var result = tool.execute(JSON.toJSON(Map.of(
            "options", List.of(Map.of("label", "A"), Map.of("label", "B"))
        )), context);

        assertFalse(result.isFailed());
        var payload = JSON.fromJSON(Map.class, ((CustomEvent) dispatched.getFirst()).data);
        assertNull(payload.get("question"));
    }

    @Test
    void rejectsOptionCountsOutsideTwoToSix() {
        var tooFew = tool.execute(JSON.toJSON(Map.of("options", List.of(Map.of("label", "Only")))), context);
        assertTrue(tooFew.isFailed());

        var options = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 7; i++) {
            options.add(Map.of("label", "option " + i));
        }
        var tooMany = tool.execute(JSON.toJSON(Map.of("options", options)), context);
        assertTrue(tooMany.isFailed());
        assertTrue(dispatched.isEmpty());
    }

    @Test
    void rejectsAnOptionWithoutALabel() {
        var result = tool.execute(JSON.toJSON(Map.of(
            "options", List.of(Map.of("value", "x"), Map.of("label", "B"))
        )), context);

        assertTrue(result.isFailed());
        assertTrue(dispatched.isEmpty());
    }

    @Test
    void failsWithoutASessionEventChannel() {
        var result = tool.execute(JSON.toJSON(Map.of(
            "options", List.of(Map.of("label", "A"), Map.of("label", "B"))
        )), ExecutionContext.builder().build());

        assertTrue(result.isFailed());
    }
}
