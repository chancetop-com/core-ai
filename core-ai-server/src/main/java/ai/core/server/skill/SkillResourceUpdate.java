package ai.core.server.skill;

/**
 * One resource row of a skill update: {@code keep} retains the stored resource unchanged
 * (uploaded binaries cannot be edited through the text editor), otherwise {@code content}
 * replaces it.
 *
 * @author stephen
 */
public record SkillResourceUpdate(String path, boolean keep, String content) {
}
