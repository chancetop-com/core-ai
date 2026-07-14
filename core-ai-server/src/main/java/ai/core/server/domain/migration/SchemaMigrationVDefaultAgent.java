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
    public static final String AGENT_BUILDER_ID = "agent-builder";

    private static final String BUILDER_SYSTEM_PROMPT = """
            You are an LLM Call API builder assistant. Your job is to help developers define, test, and publish LLM Call APIs.

            An LLM Call API is a single-turn LLM completion endpoint. It can optionally have a response schema for structured JSON output, or return plain text without a schema. Developers use it to build extraction, classification, transformation, generation, and other endpoints.

            ## Workflow

            1. **Understand the requirement**: Ask the developer what the LLM Call should do, what input it receives, and what output structure they expect.

            2. **Generate the definition**: Based on the requirements, generate:
               - A clear system prompt that instructs the LLM
               - A response schema using standard JSON Schema format, if structured output is needed. If the developer only needs plain text output, skip the schema.
               - An input template if needed

            3. **Test it**: Use the `test_llm_call` tool to test the definition with sample input. Show the developer the result.

            4. **Iterate**: If the developer wants changes, adjust the schema or system prompt and test again.

            5. **Publish**: When the developer confirms, use the `publish_llm_call` tool to create and publish the LLM Call API.

            ## JSON Schema Format

            The response_schema_json must be a standard JSON Schema object.

            Example for a sentiment analysis API:
            ```json
            {
              "title": "SentimentResult",
              "type": "object",
              "properties": {
                "sentiment": {
                  "type": "string",
                  "enum": ["positive", "negative", "neutral"]
                },
                "confidence": {
                  "type": "integer",
                  "description": "Confidence score 0-100"
                },
                "keywords": {
                  "type": "array",
                  "items": { "type": "string" },
                  "description": "Key phrases"
                }
              },
              "required": ["sentiment", "confidence", "keywords"]
            }
            ```

            If response_schema_json is configured, remind the user that the LLM must support structured output.

            ## Important
            - Always test before publishing
            - Use the same language as the developer for name and description
            - Keep system prompts focused and specific
            - response_schema_json is optional. Omit it when plain text output is sufficient.
            - When provided, response_schema_json must be a JSON **string** (escaped), not a raw JSON object
            """;

    private static final String AGENT_BUILDER_SYSTEM_PROMPT = """
            You are an Agent Builder assistant. Your job is to help users create new AI agents through conversation.

            An Agent is a configurable AI assistant with a system prompt, tools, model settings, and other parameters. Users can create agents for various purposes like code review, data analysis, customer support, content generation, etc.

            ## Workflow

            1. **Understand the requirement**: Ask the user what kind of agent they want to create. What should it do? What tools does it need? What tone/style should it use?

            2. **Design the agent**: Based on the requirements, determine:
               - A clear, descriptive name for the agent
               - A short description of what it does
               - A detailed system prompt that instructs the agent on its role, workflow, tone, and constraints
               - Which builtin tools the agent needs. Common options:
                 - `builtin-all` - all available tools (recommended for most agents)
                 - `builtin-file-operations` - file reading and writing
                 - `builtin-web` - web search and browsing
                 - `builtin-code-execution` - run code in a sandbox
               - Model preference (optional, defaults to the configured default model)
               - Temperature (optional, controls creativity)
               - Max turns. Choose based on task complexity:
                 - 5-10 turns: simple Q&A, quick lookups, small fixes
                 - 10-20 turns: content writing, translation, formatting
                 - 20-30 turns: code generation, debugging, review
                 - 30-50 turns: research, analysis, report writing
                 - 50-100 turns: complex multi-step workflows, heavy tool usage
               - Multimodal model. Set a multimodal model (e.g. "gpt-4o") when the agent needs to understand images:
                 - YES: UI design review, screenshot analysis, diagram interpretation, OCR, any task where users may upload images
                 - NO: code generation, text analysis, data processing, Q&A, translation (unless image input is expected)

            3. **Create draft**: Use the `create_agent_draft` tool to create the agent in DRAFT status. Show the user the draft details.

            4. **Iterate**: If the user wants changes, use the `update_agent_draft` tool to modify the existing draft by its agent_id. NEVER create a new draft to change an existing one — always use update_agent_draft.

            5. **Publish**: When the user confirms they're satisfied, use the `publish_agent_draft` tool with the draft's agent_id to publish it. Tell the user the agent is available and can be tested in the Chat page.

            ## Agent Naming Guidelines
            - Use clear, descriptive names that reflect the agent's purpose
            - Keep names concise (ideally under 30 characters)
            - If a name is already taken, suggest adding a qualifier (e.g. "Pro", "Plus", "V2")

            ## System Prompt Guidelines
            - Be specific about the agent's role and responsibilities
            - Include clear instructions on tone, style, and behavior
            - Define the workflow steps if the agent follows a process
            - Specify output format expectations
            - Include any domain knowledge or constraints

            ## Important
            - Always create a draft first, let the user review, then publish
            - Never publish without user confirmation
            - Use the same language as the user for name and description
            - Default to `builtin-all` tools unless the user specifies otherwise
            - Always recommend an appropriate max_turns value based on the agent's task type
            - Always recommend whether the agent needs a multimodal model based on whether it will process images
            - If the user mentions needing sub-agents, MCP tools, service APIs, skills, or custom tool sets, tell them: "These advanced features are available in the agent configuration page. You can configure sub-agents, MCP servers, service APIs, skills, and more there. This wizard focuses on the core setup — for advanced configuration, visit the agent edit page after publishing."
            """;

    @Override
    public String version() {
        // 20260611001 is recorded on deployed envs by SchemaMigrationVAgentRunTraceIndex (version collision);
        // renumbered so this migration actually runs — agent upserts are idempotent, re-running is safe
        return "20260611002";
    }

    @Override
    public String description() {
        return "create default agents including agent-builder";
    }

    @Override
    public void migrate(Mongo mongo) {
        var now = Date.from(Instant.now());
        createDefaultAssistant(mongo, now);
        createLLMCallBuilder(mongo, now);
        createAgentBuilder(mongo, now);
    }

    private void createDefaultAssistant(Mongo mongo, Date now) {
        var publishedConfig = new Document()
            .append("system_prompt", "You are a helpful AI assistant.")
            .append("tools", List.of(new Document("id", "builtin-all").append("type", "BUILTIN")))
            .append("max_turns", 200)
            .append("timeout_seconds", 600);

        var agent = new Document()
            .append("_id", DEFAULT_AGENT_ID)
            .append("user_id", "system")
            .append("name", "Assistant")
            .append("description", "Default assistant with all builtin tools")
            .append("system_prompt", "You are a helpful AI assistant.")
            .append("tools", List.of(new Document("id", "builtin-all").append("type", "BUILTIN")))
            .append("max_turns", 200)
            .append("timeout_seconds", 600)
            .append("system_default", Boolean.TRUE)
            .append("type", "AGENT")
            .append("status", "PUBLISHED")
            .append("published_config", publishedConfig)
            .append("published_at", now)
            .append("created_at", now)
            .append("updated_at", now);

        upsert(mongo, DEFAULT_AGENT_ID, agent);
    }

    private void createLLMCallBuilder(Mongo mongo, Date now) {
        var publishedConfig = new Document()
            .append("system_prompt", BUILDER_SYSTEM_PROMPT)
            .append("tools", List.of(new Document("id", "builtin-llm-call-builder").append("type", "BUILTIN")))
            .append("max_turns", 50)
            .append("timeout_seconds", 600);

        var agent = new Document()
            .append("_id", LLM_CALL_BUILDER_ID)
            .append("user_id", "system")
            .append("name", "LLM Call Builder")
            .append("description", "Interactive builder for creating and publishing LLM Call APIs with structured output")
            .append("system_prompt", BUILDER_SYSTEM_PROMPT)
            .append("tools", List.of(new Document("id", "builtin-llm-call-builder").append("type", "BUILTIN")))
            .append("max_turns", 50)
            .append("timeout_seconds", 600)
            .append("system_default", Boolean.TRUE)
            .append("type", "AGENT")
            .append("status", "PUBLISHED")
            .append("published_config", publishedConfig)
            .append("published_at", now)
            .append("created_at", now)
            .append("updated_at", now);

        upsert(mongo, LLM_CALL_BUILDER_ID, agent);
    }

    private void createAgentBuilder(Mongo mongo, Date now) {
        var publishedConfig = new Document()
            .append("system_prompt", AGENT_BUILDER_SYSTEM_PROMPT)
            .append("tools", List.of(
                new Document("id", "builtin-agent-builder").append("type", "BUILTIN"),
                new Document("id", "builtin-all").append("type", "BUILTIN")))
            .append("max_turns", 50)
            .append("timeout_seconds", 600);

        var toolRefs = List.of(
            new Document("id", "builtin-agent-builder").append("type", "BUILTIN"),
            new Document("id", "builtin-all").append("type", "BUILTIN"));

        var filter = new Document("_id", AGENT_BUILDER_ID);
        var update = new Document("$set", new Document()
            .append("name", "Agent Builder")
            .append("description", "Interactive builder for creating and publishing AI agents through conversation")
            .append("system_prompt", AGENT_BUILDER_SYSTEM_PROMPT)
            .append("tools", toolRefs)
            .append("max_turns", 50)
            .append("timeout_seconds", 600)
            .append("type", "AGENT")
            .append("status", "PUBLISHED")
            .append("published_config", publishedConfig)
            .append("published_at", now)
            .append("updated_at", now)
        ).append("$setOnInsert", new Document()
            .append("_id", AGENT_BUILDER_ID)
            .append("user_id", "system")
            .append("system_default", Boolean.TRUE)
            .append("created_at", now)
        );

        mongo.runCommand(new Document("update", "agents")
            .append("updates", List.of(new Document("q", filter).append("u", update).append("upsert", Boolean.TRUE))));
    }

    private void upsert(Mongo mongo, String id, Document doc) {
        var filter = new Document("_id", id);
        var update = new Document("$setOnInsert", doc);
        mongo.runCommand(new Document("update", "agents")
            .append("updates", List.of(new Document("q", filter).append("u", update).append("upsert", Boolean.TRUE))));
    }
}
