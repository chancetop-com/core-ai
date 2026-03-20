package ai.core.skill;

import java.util.List;

/**
 * Abstraction for skill sources. Implementations provide skills
 * from different backends: filesystem, database, remote API, etc.
 *
 * @author stephen
 */
public interface SkillProvider {

    List<SkillMetadata> listSkills();

    String readContent(SkillMetadata skill);

    String readResource(SkillMetadata skill, String resourcePath);

    int priority();
}
