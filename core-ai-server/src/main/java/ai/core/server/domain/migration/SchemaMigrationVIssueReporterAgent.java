package ai.core.server.domain.migration;

import core.framework.mongo.Mongo;
import org.bson.Document;

import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * @author stephen
 */
public class SchemaMigrationVIssueReporterAgent implements SchemaMigration {
    public static final String ISSUE_REPORTER_ID = "core-ai-issue-reporter";

    private static final String SYSTEM_PROMPT = """
            You are the core-ai Issue Reporter. Your job is to help users report issues, bugs, or feature requests for the core-ai platform by creating GitHub issues in the chancetop-com/core-ai repository.

            ## Workflow

            1. **Collect information**: Ask the user about:
               - **Issue Type**: Bug, feature request, performance problem, or other
               - **Title**: A concise summary of the issue
               - **Description**: What happened? What did the user expect to happen?
               - **Steps to Reproduce**: If it's a bug, what steps lead to the issue?
               - **Screenshots**: Encourage the user to attach screenshots or images showing the problem — you can see and analyze images
               - **Environment**: Browser, OS, core-ai version, and any relevant context
               - **Labels**: Suggest appropriate labels (bug, enhancement, question, etc.)

            2. **Confirm with user**: Summarize the issue clearly and ask the user to confirm before creating it.

            3. **Create the GitHub issue**: Once confirmed, use the `require_github_installation_token` tool to obtain a GitHub token for the `chancetop-com/core-ai` repository. Then use `web_fetch` to call the GitHub Issues API:
               ```
               POST https://api.github.com/repos/chancetop-com/core-ai/issues
               Authorization: Bearer <token>
               Content-Type: application/json
               Body: {"title": "...", "body": "...", "labels": ["..."]}
               ```
               Report the issue URL back to the user.

            If the user wants to attach logs or traces, help them find and include relevant information in the issue body.

            Be friendly, patient, and thorough. Help the user feel heard and ensure nothing is missed.
            """;

    @Override
    public String version() {
        return "20260706001";
    }

    @Override
    public String description() {
        return "create core-ai-issue-reporter default agent";
    }

    @Override
    public void migrate(Mongo mongo) {
        var now = Date.from(Instant.now());
        createIssueReporter(mongo, now);
        updateSystemPrompt(mongo, now);
    }

    private void updateSystemPrompt(Mongo mongo, Date now) {
        var filter = new Document("_id", ISSUE_REPORTER_ID);
        var set = new Document()
                .append("system_prompt", SYSTEM_PROMPT)
                .append("published_config.system_prompt", SYSTEM_PROMPT)
                .append("updated_at", now);
        mongo.runCommand(new Document("update", "agents")
                .append("updates", List.of(new Document("q", filter).append("u", new Document("$set", set)))));
    }

    private void createIssueReporter(Mongo mongo, Date now) {
        var publishedConfig = new Document()
            .append("system_prompt", SYSTEM_PROMPT)
            .append("tools", List.of(new Document("id", "builtin-all").append("type", "BUILTIN")))
            .append("multi_modal_model", "azure/responses/gpt-5-mini")
            .append("max_turns", 100)
            .append("timeout_seconds", 600);

        var agent = new Document()
            .append("_id", ISSUE_REPORTER_ID)
            .append("user_id", "system")
            .append("name", "Issue Reporter")
            .append("description", "Report issues, bugs, and feature requests for the core-ai platform")
            .append("system_prompt", SYSTEM_PROMPT)
            .append("tools", List.of(new Document("id", "builtin-all").append("type", "BUILTIN")))
            .append("multi_modal_model", "azure/responses/gpt-5-mini")
            .append("max_turns", 100)
            .append("timeout_seconds", 600)
            .append("system_default", true)
            .append("type", "AGENT")
            .append("status", "PUBLISHED")
            .append("published_config", publishedConfig)
            .append("published_at", now)
            .append("created_at", now)
            .append("updated_at", now);

        upsert(mongo, ISSUE_REPORTER_ID, agent);
    }

    private void upsert(Mongo mongo, String id, Document doc) {
        var filter = new Document("_id", id);
        var update = new Document("$setOnInsert", doc);
        mongo.runCommand(new Document("update", "agents")
            .append("updates", List.of(new Document("q", filter).append("u", update).append("upsert", true))));
    }
}
