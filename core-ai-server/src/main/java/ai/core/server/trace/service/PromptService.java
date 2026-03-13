package ai.core.server.trace.service;

import com.mongodb.client.model.Sorts;

import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

import ai.core.server.trace.domain.PromptStatus;
import ai.core.server.trace.domain.PromptTemplate;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * @author Xander
 */
public class PromptService {
    @Inject
    MongoCollection<PromptTemplate> promptCollection;

    public List<PromptTemplate> list(int offset, int limit) {
        var query = new Query();
        query.skip = offset;
        query.limit = limit;
        query.sort = Sorts.descending("updated_at");
        return promptCollection.find(query);
    }

    public PromptTemplate get(String id) {
        return promptCollection.get(id).orElse(null);
    }

    public PromptTemplate create(PromptTemplate template) {
        template.version = 1;
        template.status = PromptStatus.DRAFT;
        template.createdAt = ZonedDateTime.now();
        template.updatedAt = ZonedDateTime.now();
        promptCollection.insert(template);
        return template;
    }

    public PromptTemplate update(String id, PromptTemplate template) {
        var existing = promptCollection.get(id).orElseThrow();
        existing.name = template.name;
        existing.description = template.description;
        existing.template = template.template;
        existing.variables = template.variables;
        existing.model = template.model;
        existing.modelParameters = template.modelParameters;
        existing.tags = template.tags;
        existing.version = existing.version + 1;
        existing.updatedAt = ZonedDateTime.now();
        promptCollection.replace(existing);
        return existing;
    }

    public void delete(String id) {
        promptCollection.delete(id);
    }

    public PromptTemplate publish(String id) {
        var template = promptCollection.get(id).orElseThrow();
        template.status = PromptStatus.PUBLISHED;
        template.publishedVersion = template.version;
        template.updatedAt = ZonedDateTime.now();
        promptCollection.replace(template);
        return template;
    }
}
