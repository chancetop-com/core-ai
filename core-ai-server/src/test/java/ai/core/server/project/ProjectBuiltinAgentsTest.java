package ai.core.server.project;

import ai.core.api.jsonschema.JsonSchema;
import ai.core.server.run.ResponseSchemaConverter;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The builtin response schemas are assembled from string halves and parsed into {@link JsonSchema} at
 * run time: a malformed half (an unbalanced brace, a root keyword nested inside "properties") fails
 * the parse and takes down every LLM run of that writer, and with it the whole pipeline stage. Each
 * schema is therefore parsed here through the very path a run uses.
 *
 * @author core-ai
 */
class ProjectBuiltinAgentsTest {
    @Test
    void attributionSchemaParsesWithRootLevelRequired() {
        var schema = parse(ProjectBuiltinAgents.attributionSchema());

        assertEquals(Set.of("attributions", "new_subjects"), schema.properties.keySet());
        assertEquals(List.of("attributions", "new_subjects"), schema.required);
    }

    @Test
    void proposalTargetsCarryTheirOwnRequiredFields() {
        var schema = parse(ProjectBuiltinAgents.attributionSchema());
        var proposal = schema.properties.get("new_subjects").items.properties.get("targets").items;

        assertEquals(List.of("target_type", "target_id"), proposal.required);
        assertEquals(JsonSchema.PropertyType.ARRAY, schema.properties.get("attributions").type);
    }

    @Test
    void subjectAnalysisSchemaParsesWithRootLevelRequired() {
        var schema = parse(ProjectBuiltinAgents.subjectAnalysisSchema());

        assertEquals(Set.of("status", "kpis", "action_items", "notes", "profile"), schema.properties.keySet());
        assertEquals(List.of("kpis", "action_items", "notes"), schema.required);
    }

    // the writer ships without a response schema (the draft is free-form markdown) and the executor
    // reads the prompt from published_config, so the doc must carry both halves
    @Test
    void playbookWriterDocIsAPublishedLlmCallWithoutSchema() {
        var doc = ProjectBuiltinAgents.playbookWriterDoc(new Date());

        assertEquals("builtin-" + ProjectBuiltinAgents.PLAYBOOK_WRITER, doc.getString("_id"));
        assertEquals("LLM_CALL", doc.getString("type"));
        assertEquals("PUBLISHED", doc.getString("status"));
        assertNull(doc.getString("response_schema"));
        var prompt = doc.getString("system_prompt");
        assertNotNull(prompt);
        assertEquals(prompt, ((Document) doc.get("published_config")).getString("system_prompt"));
    }

    private JsonSchema parse(String schema) {
        var format = ResponseSchemaConverter.fromJsonSchema(schema);
        assertNotNull(format, "response schema must be a JSON object: " + schema);
        return (JsonSchema) format.jsonSchema.schema;
    }
}
