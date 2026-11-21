package ai.core.server.evaluation;

import ai.core.server.evaluation.service.EvalDatasetParser;
import ai.core.server.evaluation.service.EvalValidationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class EvalDatasetParserTest {
    private final EvalDatasetParser parser = new EvalDatasetParser();

    @Test
    void parsesValidJsonlAndKeepsDefaultSplitAndWeight() {
        var parsed = parser.parse("""
            {"case_id":"text-001","input":{"text":"2+2?"},"reference":{"output":"4"},"grader_refs":["exact"]}
            """);
        assertEquals(1, parsed.cases.size());
        var item = parsed.cases.get(0);
        assertEquals("text-001", item.caseId());
        assertEquals("DEV", item.split());
        assertEquals(1.0, item.weight(), 1e-9);
        assertTrue(item.bodyJson().contains("\"output\":\"4\""));
    }

    @Test
    void rejectsDuplicateCaseId() {
        var jsonl = """
            {"case_id":"a","input":{"text":"x"},"reference":{"output":"y"},"grader_refs":["g"]}
            {"case_id":"a","input":{"text":"x"},"reference":{"output":"y"},"grader_refs":["g"]}
            """;
        var error = assertThrows(EvalValidationException.class, () -> parser.parse(jsonl));
        assertTrue(error.getMessage().contains("duplicate case_id"));
    }

    @Test
    void rejectsUnknownFieldsAndAllowsReferenceToBeOmitted() {
        var unknown = """
            {"case_id":"a","input":{"text":"x"},"reference":{"output":"y"},"grader_refs":["g"],"extra":true}
            """;
        assertThrows(EvalValidationException.class, () -> parser.parse(unknown));
        var noReference = """
            {"case_id":"a","input":{"text":"x"},"grader_refs":["regex-only"]}
            """;
        var parsed = parser.parse(noReference);
        assertEquals(1, parsed.cases.size());
    }

    @Test
    void rejectsAttachmentsInThisPhase() {
        var jsonl = """
            {"case_id":"a","input":{"text":"x","attachments":[{"path":"a.txt"}]},"reference":{"output":"y"},"grader_refs":["g"]}
            """;
        var error = assertThrows(EvalValidationException.class, () -> parser.parse(jsonl));
        assertTrue(error.getMessage().contains("attachments are not supported"));
    }

    @Test
    void parsesTypedJsonAssertions() {
        var jsonl = """
            {"case_id":"a","input":{"text":"x"},"reference":{"assertions":[{"kind":"JSON_NUMBER_EQUALS","json_pointer":"/total","expected_number":120.0,"tolerance":0.001}]},"grader_refs":["g"],"metadata":{"split":"VALIDATION","weight":2.0}}
            """;
        var parsed = parser.parse(jsonl);
        var item = parsed.cases.get(0);
        assertEquals("VALIDATION", item.split());
        assertEquals(2.0, item.weight(), 1e-9);
        assertNotNull(item.bodyJson());
    }
}
