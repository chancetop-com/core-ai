package ai.core.server.skill;

import ai.core.skill.SkillLoadException;
import ai.core.skill.SkillMetadata;
import ai.core.skill.SkillProvider;
import ai.core.server.domain.SkillDefinition;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Skill provider backed by Mongo metadata + SkillStorage for file content.
 *
 * @author stephen
 */
public class MongoSkillProvider implements SkillProvider {

    @Inject
    MongoCollection<SkillDefinition> skillCollection;

    @Inject
    SkillService skillService;

    @Inject
    SkillStorage skillStorage;

    @Override
    public List<SkillMetadata> listSkills() {
        var definitions = skillCollection.find(Filters.exists("_id"));
        return definitions.stream().map(skillService::toMetadata).toList();
    }

    @Override
    public String readContent(SkillMetadata skill) {
        var ns = skill.getNamespace();
        var name = skill.getName();
        if (ns != null && skillStorage.exists(ns, name)) {
            return skillStorage.readSkillMd(ns, name);
        }
        if (skill.getContent() != null) {
            return skill.getContent();
        }
        throw new SkillLoadException("skill content not available: " + skill.getQualifiedName());
    }

    @Override
    public String readResource(SkillMetadata skill, String resourcePath) {
        var ns = skill.getNamespace();
        var name = skill.getName();
        if (ns == null || !skillStorage.exists(ns, name)) {
            throw new SkillLoadException("skill not in storage: " + skill.getQualifiedName());
        }
        var bytes = skillStorage.readResource(ns, name, resourcePath);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public int priority() {
        return 10;
    }
}
