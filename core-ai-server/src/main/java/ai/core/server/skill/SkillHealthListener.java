package ai.core.server.skill;

/**
 * Decouples SkillService from the agent-health implementation and keeps the module binding order
 * unchanged (the listener is injected after the health module is bound).
 *
 * @author core-ai
 */
public interface SkillHealthListener {
    void onSkillDeleted(String skillId, String skillName, String actorUserId);
}
