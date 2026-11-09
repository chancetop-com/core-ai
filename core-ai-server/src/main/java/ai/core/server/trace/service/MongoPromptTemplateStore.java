package ai.core.server.trace.service;

import ai.core.server.trace.domain.PromptTemplate;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class MongoPromptTemplateStore implements PromptTemplateStore {
    @Inject
    MongoCollection<PromptTemplate> collection;

    @Override
    public List<PromptTemplate> list(int offset, int limit) {
        var query = new Query();
        query.skip = offset;
        query.limit = limit;
        query.sort = Sorts.descending("updated_at");
        return collection.find(query);
    }

    @Override
    public Optional<PromptTemplate> get(String id) {
        return collection.get(id);
    }

    @Override
    public void insert(PromptTemplate template) {
        collection.insert(template);
    }

    @Override
    public void replace(PromptTemplate template) {
        collection.replace(template);
    }

    @Override
    public void delete(String id) {
        collection.delete(id);
    }
}
