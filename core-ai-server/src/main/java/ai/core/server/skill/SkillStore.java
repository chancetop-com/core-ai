package ai.core.server.skill;

import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.SkillSourceType;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public interface SkillStore {
    Optional<SkillDefinition> get(String id);

    Optional<SkillDefinition> findByQualifiedName(String qualifiedName);

    List<SkillDefinition> findByName(String name);

    List<SkillDefinition> listByIds(Collection<String> ids);

    List<SkillDefinition> list(String namespace, SkillSourceType sourceType, Integer offset, Integer limit);

    long count(String namespace, SkillSourceType sourceType);

    List<SkillDefinition> listBySourceType(SkillSourceType sourceType);

    List<SkillDefinition> listAll();

    List<SkillDefinition> listCatalog();

    long countByNamespace(String namespace);

    long countBySourceType(SkillSourceType sourceType);

    void insert(SkillDefinition definition);

    /** Inserts only when the id does not exist yet; false means a concurrent writer won. */
    boolean insertIfAbsent(SkillDefinition definition);

    void replace(SkillDefinition definition);

    /** Replaces the document only when its current revision equals {@code expectedRevision}. */
    boolean replaceIfRevision(SkillDefinition definition, int expectedRevision);

    void delete(String id);

    void recordShow(String qualifiedName);

    void recordPull(String id);
}
