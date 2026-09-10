package ai.core.server.project;

import ai.core.api.jsonschema.JsonSchema;
import ai.core.server.run.ResponseSchemaConverter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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

    private JsonSchema parse(String schema) {
        var format = ResponseSchemaConverter.fromJsonSchema(schema);
        assertNotNull(format, "response schema must be a JSON object: " + schema);
        return (JsonSchema) format.jsonSchema.schema;
    }
}
