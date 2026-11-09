package ai.core.server.systemprompt;

import ai.core.server.domain.SystemPrompt;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

import java.util.List;

/**
 * @author stephen
 */
public class MongoSystemPromptStore implements SystemPromptStore {
    @Inject
    MongoCollection<SystemPrompt> collection;

    @Override
    public void insert(SystemPrompt prompt) {
        collection.insert(prompt);
    }

    @Override
    public SystemPrompt latestByPromptId(String promptId) {
        return collection.find(query(Filters.eq("prompt_id", promptId))).stream().findFirst().orElse(null);
    }

    @Override
    public SystemPrompt latestByName(String name) {
        return collection.find(query(Filters.eq("name", name))).stream().findFirst().orElse(null);
    }

    @Override
    public List<SystemPrompt> listAll() {
        var query = new Query();
        query.sort = Sorts.descending("created_at");
        return collection.find(query);
    }

    @Override
    public List<SystemPrompt> versions(String promptId) {
        var query = new Query();
        query.filter = Filters.eq("prompt_id", promptId);
        query.sort = Sorts.descending("version");
        return collection.find(query);
    }

    @Override
    public SystemPrompt version(String promptId, int version) {
        var query = new Query();
        query.filter = Filters.and(Filters.eq("prompt_id", promptId), Filters.eq("version", version));
        return collection.find(query).stream().findFirst().orElse(null);
    }

    @Override
    public void deleteByPromptId(String promptId) {
        collection.delete(Filters.eq("prompt_id", promptId));
    }

    private Query query(org.bson.conversions.Bson filter) {
        var query = new Query();
        query.filter = filter;
        query.sort = Sorts.descending("version");
        query.limit = 1;
        return query;
    }
}
