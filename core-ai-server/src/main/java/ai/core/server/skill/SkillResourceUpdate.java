package ai.core.server.skill;

import ai.core.api.server.skill.UpdateSkillRequest.SkillResourceRequest;

import java.util.List;

/**
 * One resource row of a skill update: {@code keep} retains the stored resource unchanged
 * (uploaded binaries cannot be edited through the text editor), otherwise {@code content}
 * replaces it.
 *
 * @author stephen
 */
public record SkillResourceUpdate(String path, boolean keep, String content) {
    public static List<SkillResourceUpdate> fromRequests(List<SkillResourceRequest> resources) {
        if (resources == null) return null;
        return resources.stream()
            .map(resource -> new SkillResourceUpdate(resource.path, Boolean.TRUE.equals(resource.keep), resource.content))
            .toList();
    }
}
