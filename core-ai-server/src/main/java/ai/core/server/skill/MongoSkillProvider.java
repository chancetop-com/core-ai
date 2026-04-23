package ai.core.server.skill;

import ai.core.server.domain.SkillDefinition;
import ai.core.skill.SkillLoadException;
import ai.core.skill.SkillMetadata;
import ai.core.skill.SkillProvider;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;

import java.util.List;
import java.util.Set;

/**
 * Skill source backed by Mongo. Does NOT implement SkillProvider directly — callers
 * must obtain a scoped provider via {@link #scoped(Set)} so only explicitly allowed
 * skill ids are visible to an Agent. This prevents an Agent from discovering skills
 * that were never attached to it.
 *
 * @author stephen
 */
public class MongoSkillProvider {

    @Inject
    MongoCollection<SkillDefinition> skillCollection;

    @Inject
    SkillService skillService;

    /**
     * Returns a SkillProvider that only lists skills whose id is in {@code allowedIds}.
     * The set is held by reference, so callers may mutate it to grow the allowed set;
     * call {@link ai.core.skill.SkillRegistry#invalidateCache()} afterwards to force a re-list.
     */
    public SkillProvider scoped(Set<String> allowedIds) {
        return new ScopedProvider(allowedIds);
    }

    private final class ScopedProvider implements SkillProvider {
        private final Set<String> allowedIds;

        ScopedProvider(Set<String> allowedIds) {
            this.allowedIds = allowedIds;
        }

        @Override
        public List<SkillMetadata> listSkills() {
            if (allowedIds.isEmpty()) return List.of();
            var snapshot = List.copyOf(allowedIds);
            var definitions = skillCollection.find(Filters.in("_id", snapshot));
            return definitions.stream().map(skillService::toMetadata).toList();
        }

        @Override
        public String readContent(SkillMetadata skill) {
            if (skill.getContent() != null) return skill.getContent();
            throw new SkillLoadException("skill content not available: " + skill.getQualifiedName());
        }

        @Override
        public String readResource(SkillMetadata skill, String resourcePath) {
            var def = skillService.findByQualifiedName(skill.getQualifiedName());
            if (def.resources == null || def.resources.isEmpty()) {
                throw new SkillLoadException("skill has no resources: " + skill.getQualifiedName());
            }
            return def.resources.stream()
                .filter(r -> r.path.equals(resourcePath))
                .findFirst()
                .map(r -> r.content)
                .orElseThrow(() -> new SkillLoadException("resource not found: " + resourcePath + " in " + skill.getQualifiedName()));
        }

        @Override
        public int priority() {
            return 10;
        }
    }
}
