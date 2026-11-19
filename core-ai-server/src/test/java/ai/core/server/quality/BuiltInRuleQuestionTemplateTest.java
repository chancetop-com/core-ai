package ai.core.server.quality;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltInRuleQuestionTemplateTest {
    @Test
    void capabilityRuleExposesOneQuestionPerCapability() {
        var questions = new BuiltinCapabilityRule().template().questions();

        assertEquals(EffectiveCapabilityResolver.CAPABILITIES.size(), questions.size());
        assertEquals(List.of("intent_FILE_READ", "intent_FILE_WRITE", "intent_WEB_SEARCH", "intent_WEB_FETCH",
                "intent_CODE_EXECUTE", "intent_IMAGE_GENERATE", "intent_IMAGE_EDIT", "intent_VIDEO_GENERATE"),
            questions.stream().map(QualityRuleEvaluator.QuestionSpec::name).toList());
        questions.forEach(question -> {
            assertEquals("noul", question.type());
            assertTrue(question.instruction() != null && !question.instruction().isBlank());
            assertEquals(List.of("yes", "no"), List.copyOf(question.criteria().keySet()));
        });
    }

    @Test
    void semanticBuiltInsExposeTheQuestionTheyActuallyAsk() {
        var time = new TimeContextRule().template().questions();
        assertEquals(1, time.size());
        assertEquals(TimeContextRule.QUESTION, time.get(0).name());

        var summary = new FunctionSummaryRule().template().questions();
        assertEquals(1, summary.size());
        assertEquals(FunctionSummaryRule.QUESTION, summary.get(0).name());

        var binding = new ResourceBindingRule().template().questions();
        assertEquals(1, binding.size());
        assertEquals(ResourceBindingRule.GLOBAL_QUESTION, binding.get(0).name());
    }

    @Test
    void codeRulesDoNotPretendToAskQuestions() {
        assertTrue(new ReferenceAvailableRule().template().questions().isEmpty());
        assertTrue(new McpServerAvailableRule().template().questions().isEmpty());
        assertTrue(new ResourceCountLimitRule().template().questions().isEmpty());
    }
}
