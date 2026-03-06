package ai.core.skill;

import java.util.List;
import java.util.stream.Collectors;

/**
 * @author xander
 */
public class SkillPromptFormatter {
    private static final String SKILLS_HEADER = """

        ## Skills

        **IMPORTANT: You MUST check available skills BEFORE attempting any task yourself.**
        If a skill matches the user's request, you MUST use it — do NOT attempt to accomplish the task without the skill.
        Skills provide tested, reliable workflows. Read the full SKILL.md first when you need to use a skill.

        **Available Skills:**

        """;

    private static final String SKILLS_FOOTER = """

        **How to use (MANDATORY when a skill matches):**
        1. Check if the user's request matches any skill's description above
        2. If matched, IMMEDIATELY use read_file to read the corresponding SKILL.md
        3. Follow the workflow in SKILL.md exactly to execute the task
        4. Use absolute paths when executing auxiliary scripts from skills
        5. Do NOT try alternative approaches (e.g. pip install, manual scripting) when a skill is available
        """;

    private static final String SKILL_ENTRY_FORMAT = "- **%s**: %s\n  → Read `%s` for full instructions\n";
    private static final String SKILL_RESOURCES_LINE = "  → Available resources: %s\n";
    private static final int ESTIMATED_SIZE_PER_SKILL = 200;

    public String format(List<SkillMetadata> skills) {
        if (skills == null || skills.isEmpty()) {
            return "";
        }
        int estimatedSize = SKILLS_HEADER.length() + SKILLS_FOOTER.length() + skills.size() * ESTIMATED_SIZE_PER_SKILL;
        var sb = new StringBuilder(estimatedSize);
        sb.append(SKILLS_HEADER);
        for (var skill : skills) {
            sb.append(formatSkillEntry(skill));
        }
        sb.append(SKILLS_FOOTER);
        return sb.toString();
    }

    private String formatSkillEntry(SkillMetadata skill) {
        String base = String.format(SKILL_ENTRY_FORMAT, skill.getName(), skill.getDescription(), skill.getPath());
        List<String> resources = skill.getResources();
        if (resources == null || resources.isEmpty()) {
            return base;
        }
        String resourceList = resources.stream()
                .map(r -> "`" + r + "`")
                .collect(Collectors.joining(", "));
        return base + String.format(SKILL_RESOURCES_LINE, resourceList);
    }
}
