package ai.core.server.domain.migration;

import core.framework.mongo.Mongo;
import org.bson.Document;

import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * @author stephen
 */
public class SchemaMigrationVDefaultAgent implements SchemaMigration {
    public static final String DEFAULT_AGENT_ID = "default-assistant";
    public static final String LLM_CALL_BUILDER_ID = "llm-call-builder";

    private static final String BUILDER_SYSTEM_PROMPT = """
            You are an LLM Call API builder assistant. Your job is to help developers define, test, and publish LLM Call APIs.

            An LLM Call API is a single-turn LLM completion endpoint. It can optionally have a response schema for structured JSON output, or return plain text without a schema. Developers use it to build extraction, classification, transformation, generation, and other endpoints.

            ## Workflow

            1. **Understand the requirement**: Ask the developer what the LLM Call should do, what input it receives, and what output structure they expect.

            2. **Generate the definition**: Based on the requirements, generate:
               - A clear system prompt that instructs the LLM
               - A response schema using ApiDefinitionType format (JSON array), if structured output is needed. If the developer only needs plain text output, skip the schema.
               - An input template if needed

            3. **Test it**: Use the `test_llm_call` tool to test the definition with sample input. Show the developer the result.

            4. **Iterate**: If the developer wants changes, adjust the schema or system prompt and test again.

            5. **Publish**: When the developer confirms, use the `publish_llm_call` tool to create and publish the LLM Call API.

            ## ApiDefinitionType Schema Format

            The response_schema_json must be a JSON array of type definitions. The first element is the root response type.

            Example for a sentiment analysis API:
            ```json
            [
              {
                "name": "SentimentResult",
                "type": "CLASS",
                "fields": [
                  {"name": "sentiment", "type": "Sentiment", "constraints": {"notNull": true}},
                  {"name": "confidence", "type": "Integer", "description": "Confidence score 0-100", "constraints": {"notNull": true}},
                  {"name": "keywords", "type": "List", "typeParams": ["String"], "description": "Key phrases", "constraints": {"notNull": true}}
                ]
              },
              {
                "name": "Sentiment",
                "type": "ENUM",
                "enumConstants": [
                  {"name": "POSITIVE", "value": "positive"},
                  {"name": "NEGATIVE", "value": "negative"},
                  {"name": "NEUTRAL", "value": "neutral"}
                ]
              }
            ]
            ```

            Supported primitive types: String, Integer, Long, Double, Boolean, LocalDate, ZonedDateTime
            Use "List" with "typeParams" for arrays, reference other type names for nested objects/enums.

            ## Important
            - Always test before publishing
            - Use the same language as the developer for name and description
            - Keep system prompts focused and specific
            - response_schema_json is optional. Omit it when plain text output is sufficient.
            - When provided, response_schema_json must be a JSON **string** (escaped), not a raw JSON object
            """;

    @Override
    public String version() {
        return "20260306002";
    }

    @Override
    public String description() {
        return "create default agents";
    }

    @Override
    public void migrate(Mongo mongo) {
        var now = Date.from(Instant.now());
        createDefaultAssistant(mongo, now);
        createLLMCallBuilder(mongo, now);
    }

    private void createDefaultAssistant(Mongo mongo, Date now) {
        var publishedConfig = new Document()
            .append("system_prompt", "You are a helpful AI assistant.")
            .append("tool_ids", List.of("builtin-all"))
            .append("max_turns", 100)
            .append("timeout_seconds", 600);

        var agent = new Document()
            .append("_id", DEFAULT_AGENT_ID)
            .append("user_id", "system")
            .append("name", "Assistant")
            .append("description", "Default assistant with all builtin tools")
            .append("system_prompt", "You are a helpful AI assistant.")
            .append("tool_ids", List.of("builtin-all"))
            .append("max_turns", 100)
            .append("timeout_seconds", 600)
            .append("system_default", true)
            .append("type", "AGENT")
            .append("status", "PUBLISHED")
            .append("published_config", publishedConfig)
            .append("published_at", now)
            .append("created_at", now)
            .append("updated_at", now);

        upsert(mongo, DEFAULT_AGENT_ID, agent);
    }

    private void createLLMCallBuilder(Mongo mongo, Date now) {
        var toolIds = List.of("builtin-llm-call-builder");

        var publishedConfig = new Document()
            .append("system_prompt", BUILDER_SYSTEM_PROMPT)
            .append("tool_ids", toolIds)
            .append("max_turns", 50)
            .append("timeout_seconds", 600);

        var agent = new Document()
            .append("_id", LLM_CALL_BUILDER_ID)
            .append("user_id", "system")
            .append("name", "LLM Call Builder")
            .append("description", "Interactive builder for creating and publishing LLM Call APIs with structured output")
            .append("system_prompt", BUILDER_SYSTEM_PROMPT)
            .append("tool_ids", toolIds)
            .append("max_turns", 50)
            .append("timeout_seconds", 600)
            .append("system_default", true)
            .append("type", "AGENT")
            .append("status", "PUBLISHED")
            .append("published_config", publishedConfig)
            .append("published_at", now)
            .append("created_at", now)
            .append("updated_at", now);

        upsert(mongo, LLM_CALL_BUILDER_ID, agent);
    }

    private void upsert(Mongo mongo, String id, Document doc) {
        var filter = new Document("_id", id);
        var update = new Document("$setOnInsert", doc);
        mongo.runCommand(new Document("update", "agents")
            .append("updates", List.of(new Document("q", filter).append("u", update).append("upsert", true))));
    }
}
