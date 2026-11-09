package ai.core.server.trace.service;

import core.framework.inject.Inject;

import ai.core.server.trace.domain.PromptStatus;
import ai.core.server.trace.domain.PromptTemplate;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * @author Xander
 */
public class PromptService {
    @Inject
    PromptTemplateStore store;

    public List<PromptTemplate> list(int offset, int limit) {
        return store.list(offset, limit);
    }

    public PromptTemplate get(String id) {
        return store.get(id).orElse(null);
    }

    @SuppressFBWarnings("CFS_CONFUSING_FUNCTION_SEMANTICS")
    public PromptTemplate create(PromptTemplate template) {
        template.id = java.util.UUID.randomUUID().toString();
        template.version = 1;
        template.status = PromptStatus.DRAFT;
        template.createdAt = ZonedDateTime.now();
        template.updatedAt = ZonedDateTime.now();
        store.insert(template);
        return template;
    }

    public PromptTemplate update(String id, PromptTemplate template) {
        var existing = store.get(id).orElseThrow();
        existing.name = template.name;
        existing.description = template.description;
        existing.template = template.template;
        existing.variables = template.variables;
        existing.model = template.model;
        existing.modelParameters = template.modelParameters;
        existing.tags = template.tags;
        existing.version = existing.version + 1;
        existing.updatedAt = ZonedDateTime.now();
        store.replace(existing);
        return existing;
    }

    public void delete(String id) {
        store.delete(id);
    }

    public PromptTemplate publish(String id) {
        var template = store.get(id).orElseThrow();
        template.status = PromptStatus.PUBLISHED;
        template.publishedVersion = template.version;
        template.updatedAt = ZonedDateTime.now();
        store.replace(template);
        return template;
    }
}
