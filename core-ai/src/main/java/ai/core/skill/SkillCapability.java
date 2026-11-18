package ai.core.skill;

import ai.core.tool.ToolCall;

/**
 * One concrete capability a skill contributes to a session's script surface.
 * <p>
 * The declaration is session-scoped and only describes an authorization; it is not part of the
 * model's tool registry and carries no provenance about which script process issues a call.
 *
 * @author core-ai
 */
public record SkillCapability(String skillId, String qualifiedName, String kind, String refId,
                              String path, String group, ToolCall tool) {
}
