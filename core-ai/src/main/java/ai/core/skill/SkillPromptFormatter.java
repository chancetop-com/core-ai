package ai.core.skill;

import java.util.List;

/**
 * @author xander
 */
public class SkillPromptFormatter {
    private static final String SKILLS_HEADER = """

        ## Skills

        You can use the following skills to accomplish specialized tasks.
        Skills use progressive disclosure - you only see names and descriptions here.
        Read the full SKILL.md when you need to use a skill.

        **Available Skills:**

        """;

    private static final String SKILLS_FOOTER = """

        **How to use:**
        1. Determine if the user's request matches a skill's description
        2. If matched, use read_file tool to read the corresponding SKILL.md for full instructions
        3. Follow the workflow in SKILL.md to execute the task
        4. Use absolute paths when executing auxiliary scripts from skills
        """;

    private static final String SKILL_ENTRY_FORMAT = "- **%s**: %s\n  → Read `%s` for full instructions\n";
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
        return String.format(SKILL_ENTRY_FORMAT, skill.getName(), skill.getDescription(), skill.getPath());
    }
}
