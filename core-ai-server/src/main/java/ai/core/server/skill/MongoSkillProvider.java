package ai.core.server.skill;

import ai.core.server.domain.SkillDefinition;
import ai.core.skill.SkillLoadException;
import ai.core.skill.SkillMetadata;
import ai.core.skill.SkillProvider;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;

import java.util.List;

/**
 * Skill provider backed entirely by Mongo. SKILL.md content and resource
 * file bodies live in the `skills` collection; no external filesystem.
 *
 * @author stephen
 */
public class MongoSkillProvider implements SkillProvider {

    @Inject
    MongoCollection<SkillDefinition> skillCollection;

    @Inject
    SkillService skillService;

    @Override
    public List<SkillMetadata> listSkills() {
        var definitions = skillCollection.find(Filters.exists("_id"));
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
