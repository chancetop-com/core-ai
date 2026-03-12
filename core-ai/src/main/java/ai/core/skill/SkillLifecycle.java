package ai.core.skill;

import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * @author xander
 */
public class SkillLifecycle extends AbstractLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkillLifecycle.class);

    private final SkillConfig config;
    private final SkillLoader loader;
    private volatile List<SkillMetadata> loadedSkills;

    public SkillLifecycle(SkillConfig config) {
        this.config = config;
        this.loader = new SkillLoader(config.getMaxSkillFileSize());
    }

    @Override
    public void afterAgentBuild(Agent agent) {
        if (!config.isEnabled()) return;
        this.loadedSkills = loader.loadAll(config.getSources());
        LOGGER.debug("Loaded {} skills", loadedSkills.size());
        if (agent != null && !loadedSkills.isEmpty()) {
            injectSkillsToSystemPrompt(agent);
        }
    }

    private static final String SKILLS_PLACEHOLDER = "{{AVAILABLE_SKILLS}}";

    private void injectSkillsToSystemPrompt(Agent agent) {
        var sb = new StringBuilder(2048);
        sb.append("<available_skills>\n");
        for (var skill : loadedSkills) {
            sb.append("<skill>\n");
            sb.append("<name>").append(skill.getName()).append("</name>\n");
            sb.append("<description>").append(skill.getDescription()).append("</description>\n");
            sb.append("<location>").append(skill.getPath()).append("</location>\n");
            sb.append("</skill>\n");
        }
        sb.append("</available_skills>");

        String current = agent.getSystemPrompt();
        if (current != null && current.contains(SKILLS_PLACEHOLDER)) {
            agent.setSystemPrompt(current.replace(SKILLS_PLACEHOLDER, sb.toString()));
        } else {
            agent.setSystemPrompt(current == null ? sb.toString() : current + "\n" + sb);
        }
    }

    @Override
    public void beforeModel(CompletionRequest request, ExecutionContext executionContext) {
        if (request == null || request.messages == null || request.messages.isEmpty()) return;
        if (loadedSkills == null || loadedSkills.isEmpty()) return;

        String userQuery = findLastUserMessage(request.messages);
        if (userQuery == null || userQuery.isBlank()) return;

        List<String> matched = matchSkills(userQuery);
        if (matched.isEmpty()) return;

        request.messages.add(Message.of(RoleType.SYSTEM, buildSkillReminder(matched)));
        LOGGER.debug("Skill reminder: {}", matched);
    }

    List<String> matchSkills(String query) {
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        List<String> matched = new ArrayList<>();
        for (var skill : loadedSkills) {
            if (matchesSkill(skill, lowerQuery)) {
                matched.add(skill.getName());
            }
        }
        return matched;
    }

    private boolean matchesSkill(SkillMetadata skill, String lowerQuery) {
        if (lowerQuery.contains(skill.getName())) {
            return true;
        }
        String[] descWords = skill.getDescription().toLowerCase(Locale.ROOT).split("\\s+");
        int matchCount = 0;
        for (String word : descWords) {
            if (word.length() > 3 && lowerQuery.contains(word)) {
                matchCount++;
            }
        }
        return matchCount >= 2;
    }

    private String findLastUserMessage(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            var msg = messages.get(i);
            if (msg.role == RoleType.USER) {
                return msg.getTextContent();
            }
        }
        return null;
    }

    private String buildSkillReminder(List<String> skillNames) {
        var sb = new StringBuilder(128);
        sb.append("[Skill Reminder] The following skills match this request: ");
        for (int i = 0; i < skillNames.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append('\'').append(skillNames.get(i)).append('\'');
        }
        sb.append(". Use use_skill to load full instructions, or follow the skill description directly.");
        return sb.toString();
    }

    public List<SkillMetadata> getLoadedSkills() {
        return loadedSkills == null ? null : Collections.unmodifiableList(loadedSkills);
    }
}
