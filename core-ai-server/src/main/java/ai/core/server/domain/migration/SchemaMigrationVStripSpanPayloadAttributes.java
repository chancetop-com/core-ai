package ai.core.server.domain.migration;

import core.framework.mongo.Mongo;

import org.bson.Document;
import org.bson.types.MinKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Strips the duplicated payload attributes from existing span documents. The LLM/agent tracers emit
 * the full request/response payloads as span attributes (langfuse.observation.input/output and
 * gen_ai.prompt/completion), and the OTLP ingest copies them onto the span input/output fields, so
 * every span stored the same content twice. On this collection the duplicates account for roughly
 * half of ~22GB; after this migration spans keep only the small metadata attributes.
 *
 * Dotted update paths cannot address keys whose names contain dots, so a plain $unset does not work;
 * this rebuilds the attributes map server-side with $objectToArray/$filter/$arrayToObject. The
 * update is idempotent (documents without the payload keys are rewritten unchanged).
 * Iteration pages by _id (indexed) to satisfy notablescan and to keep each command small.
 *
 * @author stephen
 */
public class SchemaMigrationVStripSpanPayloadAttributes implements SchemaMigration {
    private static final Logger LOGGER = LoggerFactory.getLogger(SchemaMigrationVStripSpanPayloadAttributes.class);
    private static final List<String> PAYLOAD_ATTRIBUTE_KEYS = List.of(
        "langfuse.observation.input",
        "langfuse.observation.output",
        "gen_ai.prompt",
        "gen_ai.completion");
    private static final int PAGE_SIZE = 500;

    @Override
    public String version() {
        return "20260808004";
    }

    @Override
    public String description() {
        return "strip duplicated payload attributes from spans to halve collection size";
    }

    @Override
    public void migrate(Mongo mongo) {
        Object lastId = new MinKey();
        long total = 0;
        while (true) {
            var page = findIdPage(mongo, lastId);
            if (page.isEmpty()) break;
            lastId = page.getLast().get("_id");
            total += updateBatch(mongo, page);
            LOGGER.info("span payload attribute migration progress: processed {} spans", total);
        }
        LOGGER.info("span payload attribute migration completed: processed {} spans", total);
    }

    private List<Document> findIdPage(Mongo mongo, Object lastId) {
        var result = mongo.runCommand(new Document("find", "spans")
            .append("filter", new Document("_id", new Document("$gt", lastId)))
            .append("sort", new Document("_id", 1))
            .append("projection", new Document("_id", 1))
            .append("batchSize", PAGE_SIZE)
            .append("limit", PAGE_SIZE));
        var cursor = (Document) result.get("cursor");
        return cursor.getList("firstBatch", Document.class);
    }

    private int updateBatch(Mongo mongo, List<Document> page) {
        var ids = page.stream().map(doc -> doc.get("_id")).toList();
        var result = mongo.runCommand(new Document("update", "spans")
            .append("updates", List.of(new Document("q", new Document("_id", new Document("$in", ids)))
                .append("u", List.of(new Document("$set", new Document("attributes",
                    new Document("$arrayToObject", new Document("$filter",
                        new Document("input", new Document("$objectToArray",
                            new Document("$ifNull", List.of("$attributes", new Document()))))
                            .append("as", "kv")
                            .append("cond", new Document("$not",
                                new Document("$in", List.of("$$kv.k", PAYLOAD_ATTRIBUTE_KEYS))))))))))
                .append("multi", Boolean.TRUE))));
        return ((Number) result.get("n")).intValue();
    }
}
