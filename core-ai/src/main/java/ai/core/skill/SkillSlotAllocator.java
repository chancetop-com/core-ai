package ai.core.skill;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reserves inline prompt slots for mounted skills: up to 10 ALWAYS_ON baseline slots plus
 * session workspace slots up to PINNED_SKILL_CAP (25). Excess attached skills overflow to
 * on-demand search_skills without losing session attachment authorization.
 *
 * @author stephen
 */
public final class SkillSlotAllocator {
    public static final int PINNED_SKILL_CAP = 25;
    public static final int ALWAYS_ON_SLOT_CAP = 10;
    public static final String TIER_ALWAYS_ON = "ALWAYS_ON";

    public static Allocation allocate(List<SkillMetadata> skills) {
        if (skills == null || skills.isEmpty()) {
            return new Allocation(List.of(), List.of());
        }
        var alwaysOn = new ArrayList<SkillMetadata>(Math.min(skills.size(), ALWAYS_ON_SLOT_CAP));
        var others = new ArrayList<SkillMetadata>(skills.size());
        for (var skill : skills) {
            if (skill == null) continue;
            if (isAlwaysOn(skill)) {
                alwaysOn.add(skill);
            } else {
                others.add(skill);
            }
        }

        var pinned = new ArrayList<SkillMetadata>(Math.min(skills.size(), PINNED_SKILL_CAP));
        var overflow = new ArrayList<SkillMetadata>();

        int alwaysOnCount = 0;
        for (var skill : alwaysOn) {
            if (alwaysOnCount < ALWAYS_ON_SLOT_CAP && pinned.size() < PINNED_SKILL_CAP) {
                pinned.add(skill);
                alwaysOnCount++;
            } else {
                overflow.add(skill);
            }
        }
        for (var skill : others) {
            if (pinned.size() < PINNED_SKILL_CAP) {
                pinned.add(skill);
            } else {
                overflow.add(skill);
            }
        }
        return new Allocation(List.copyOf(pinned), List.copyOf(overflow));
    }

    private static boolean isAlwaysOn(SkillMetadata skill) {
        return skill.getTier() != null && TIER_ALWAYS_ON.equals(skill.getTier().trim().toUpperCase(Locale.ROOT));
    }

    private SkillSlotAllocator() {
    }

    public record Allocation(List<SkillMetadata> pinned, List<SkillMetadata> overflow) {
    }
}
