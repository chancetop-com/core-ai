package ai.core.server.domain.migration;

import ai.core.prompt.Prompts;
import core.framework.mongo.Mongo;
import org.bson.Document;
import org.bson.types.MinKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Gives the default assistant its product personality. The template is admin-editable but every personal
 * assistant is a fork that froze the template prompt at fork time, so appending to the template alone would
 * leave every existing user on the old voice: the paragraph is appended to the template and to the forks that
 * inherited from it. A prompt that already carries the paragraph stays untouched, which also keeps the seeded
 * template of a fresh environment from being appended twice in one run.
 *
 * @author stephen
 */
public class SchemaMigrationVDefaultAssistantPersonality implements SchemaMigration {
    private static final Logger LOGGER = LoggerFactory.getLogger(SchemaMigrationVDefaultAssistantPersonality.class);
    private static final String COLLECTION = "agents";
    private static final String TEMPLATE_ID = SchemaMigrationVDefaultAgent.DEFAULT_AGENT_ID;
    // forks are the "assistant:<userId>" documents; _id is the only indexed way to reach them (forked_from has
    // no index and notablescan rejects the resulting collection scan), so the rows are verified in code
    private static final String FORK_ID_PREFIX = "^assistant:";
    private static final String FORKED_FROM = "forked_from";
    private static final String SYSTEM_PROMPT = "system_prompt";
    private static final String PUBLISHED_CONFIG = "published_config";
    private static final String PUBLISHED_SYSTEM_PROMPT = PUBLISHED_CONFIG + "." + SYSTEM_PROMPT;
    private static final int PAGE_SIZE = 100;

    @Override
    public String version() {
        return "20260923001";
    }

    @Override
    public String description() {
        return "append the assistant personality to the default assistant template and its forks";
    }

    @Override
    public void migrate(Mongo mongo) {
        var updated = appendToTemplate(mongo) + appendToForks(mongo);
        LOGGER.info("default assistant personality appended to {} agent documents", updated);
    }

    private int appendToTemplate(Mongo mongo) {
        var result = mongo.runCommand(new Document("find", COLLECTION)
            .append("filter", new Document("_id", TEMPLATE_ID))
            .append("projection", new Document(SYSTEM_PROMPT, 1).append(PUBLISHED_SYSTEM_PROMPT, 1))
            .append("limit", 1));
        var page = firstBatch(result);
        if (page.isEmpty()) return 0;
        var template = page.getFirst();
        var fields = personalityFields(template);
        return fields.isEmpty() ? 0 : apply(mongo, List.of(update(template, fields)));
    }

    private int appendToForks(Mongo mongo) {
        var total = 0;
        Object lastId = new MinKey();
        while (true) {
            var page = forkPage(mongo, lastId);
            if (page.isEmpty()) break;
            lastId = page.getLast().get("_id");
            total += apply(mongo, forkUpdates(page));
        }
        return total;
    }

    private List<Document> forkUpdates(List<Document> page) {
        var updates = new ArrayList<Document>();
        for (var fork : page) {
            if (!TEMPLATE_ID.equals(fork.getString(FORKED_FROM))) continue;
            var fields = personalityFields(fork);
            if (!fields.isEmpty()) updates.add(update(fork, fields));
        }
        return updates;
    }

    private Document update(Document agent, Document fields) {
        return new Document("q", new Document("_id", agent.get("_id"))).append("u", new Document("$set", fields));
    }

    // only the paths that exist are rewritten, so a fork without a published snapshot never grows a bogus one
    private Document personalityFields(Document agent) {
        var fields = new Document();
        appendPersonality(fields, SYSTEM_PROMPT, agent.get(SYSTEM_PROMPT));
        var published = agent.get(PUBLISHED_CONFIG);
        if (published instanceof Document document) appendPersonality(fields, PUBLISHED_SYSTEM_PROMPT, document.get(SYSTEM_PROMPT));
        return fields;
    }

    private void appendPersonality(Document fields, String path, Object value) {
        if (!(value instanceof String prompt) || prompt.isBlank()) return;
        if (prompt.contains(Prompts.ASSISTANT_PERSONALITY_PROMPT)) return;
        fields.append(path, prompt + "\n\n" + Prompts.ASSISTANT_PERSONALITY_PROMPT);
    }

    private int apply(Mongo mongo, List<Document> updates) {
        if (updates.isEmpty()) return 0;
        var result = mongo.runCommand(new Document("update", COLLECTION).append("updates", updates));
        return ((Number) result.get("n")).intValue();
    }

    private List<Document> forkPage(Mongo mongo, Object lastId) {
        var result = mongo.runCommand(new Document("find", COLLECTION)
            .append("filter", new Document("_id", new Document("$gt", lastId).append("$regex", FORK_ID_PREFIX)))
            .append("projection", new Document(FORKED_FROM, 1).append(SYSTEM_PROMPT, 1).append(PUBLISHED_SYSTEM_PROMPT, 1))
            .append("sort", new Document("_id", 1))
            .append("batchSize", PAGE_SIZE)
            .append("limit", PAGE_SIZE));
        return firstBatch(result);
    }

    private List<Document> firstBatch(Document result) {
        var cursor = (Document) result.get("cursor");
        return cursor.getList("firstBatch", Document.class);
    }
}
