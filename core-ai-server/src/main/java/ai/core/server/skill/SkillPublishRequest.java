package ai.core.server.skill;

import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.SkillRepoConfig;
import ai.core.server.domain.SkillSourceType;

import java.util.List;
import java.util.Map;

/**
 * One write intent for a skill. The service builds the candidate from these fields and never reads the
 * SKILL.md or resources a second time.
 *
 * @author core-ai
 */
public final class SkillPublishRequest {
    public static SkillPublishRequest upload(String userId, String namespace, String directoryName,
                                             String content, Map<String, byte[]> resources) {
        var request = new SkillPublishRequest();
        request.userId = userId;
        request.namespace = namespace;
        request.directoryName = directoryName;
        request.content = content;
        request.resources = resources;
        request.sourceType = SkillSourceType.UPLOAD;
        return request;
    }

    public String userId;
    public String namespace;
    public String directoryName;
    public String content;
    public Map<String, byte[]> resources;
    public SkillDefinition existing;
    public SkillSourceType sourceType = SkillSourceType.UPLOAD;
    public SkillRepoConfig repoConfig;
    public String mirroredDescription;
    public List<String> mirroredAllowedTools;
}
