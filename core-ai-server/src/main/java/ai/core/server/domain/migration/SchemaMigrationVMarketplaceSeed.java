package ai.core.server.domain.migration;

import core.framework.mongo.Mongo;
import org.bson.Document;

import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Seeds the marketplace_repos collection with the MiniMax-AI community skills repo.
 *
 * @author stephen
 */
public class SchemaMigrationVMarketplaceSeed implements SchemaMigration {
    private static final String MINIMAX_REPO_ID = "minimax-ai-skills";

    @Override
    public String version() {
        return "20260710001";
    }

    @Override
    public String description() {
        return "seed marketplace_repos with MiniMax-AI community skills";
    }

    @Override
    public void migrate(Mongo mongo) {
        var now = Date.from(Instant.now());
        var repo = new Document()
            .append("_id", MINIMAX_REPO_ID)
            .append("name", "MiniMax-AI Community Skills")
            .append("repo_url", "https://github.com/MiniMax-AI/skills")
            .append("branch", "main")
            .append("skill_path", "skills")
            .append("description", "Official community skills from MiniMax-AI covering frontend, mobile, document generation, music, and more.")
            .append("skill_count", 17)
            .append("featured", true)
            .append("category", "development")
            .append("created_at", now)
            .append("updated_at", now);

        var filter = new Document("_id", MINIMAX_REPO_ID);
        var update = new Document("$setOnInsert", repo);
        mongo.runCommand(new Document("update", "marketplace_repos")
            .append("updates", List.of(new Document("q", filter).append("u", update).append("upsert", true))));
    }
}
