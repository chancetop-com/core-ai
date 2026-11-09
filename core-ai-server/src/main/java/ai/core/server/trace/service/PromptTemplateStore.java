package ai.core.server.trace.service;

import ai.core.server.trace.domain.PromptTemplate;

import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public interface PromptTemplateStore {
    List<PromptTemplate> list(int offset, int limit);

    Optional<PromptTemplate> get(String id);

    void insert(PromptTemplate template);

    void replace(PromptTemplate template);

    void delete(String id);
}
