package ai.core.server.skill;

import ai.core.server.domain.SkillDefinition;
import ai.core.skill.SkillLoadException;
import ai.core.skill.SkillMetadata;
import ai.core.skill.SkillProvider;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;

import java.util.Collections;
import java.util.List;

/**
 * @author stephen
 */
public class MongoSkillProvider implements SkillProvider {

    @Inject
    MongoCollection<SkillDefinition> skillCollection;

    @Override
    public List<SkillMetadata> listSkills() {
        var definitions = skillCollection.find(Filters.exists("_id"));
        return definitions.stream().map(this::toMetadata).toList();
    }

    @Override
    public String readContent(SkillMetadata skill) {
        if (skill.getContent() != null) {
            return skill.getContent();
        }
        throw new SkillLoadException("skill content not available: " + skill.getQualifiedName());
    }

    @Override
    public String readResource(SkillMetadata skill, String resourcePath) {
        throw new SkillLoadException("resource reading not supported for MongoDB-backed skills");
    }

    @Override
    public int priority() {
        return 10;
    }

    private SkillMetadata toMetadata(SkillDefinition def) {
        return SkillMetadata.builder(def.name, def.description, null)
            .namespace(def.namespace)
            .content(def.content)
            .allowedTools(def.allowedTools != null ? def.allowedTools : Collections.emptyList())
            .metadata(def.metadata != null ? def.metadata : Collections.emptyMap())
            .resources(def.resources != null ? def.resources.stream().map(r -> r.path).toList() : Collections.emptyList())
            .build();
    }
}
