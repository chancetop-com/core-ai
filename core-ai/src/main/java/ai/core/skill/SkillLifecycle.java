package ai.core.skill;

import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        if (agent != null) {
            injectSystemSkills(agent);
        }
    }

    private void injectSystemSkills(Agent agent) {
        var sb = new StringBuilder(1024);
        for (var skill : loadedSkills) {
            if (!skill.isSystemSkill()) continue;
            String content = readSkillFile(skill);
            if (content != null) {
                sb.append("\n<system-skill name=\"")
                        .append(skill.getName())
                        .append("\">\n")
                        .append(content)
                        .append("\n</system-skill>\n");
                LOGGER.debug("Injected system skill: {}", skill.getName());
            }
        }
        if (!sb.isEmpty()) {
            String current = agent.getSystemPrompt();
            agent.setSystemPrompt(current == null ? sb.toString() : current + sb);
        }
    }

    private String readSkillFile(SkillMetadata skill) {
        try {
            byte[] bytes = Files.readAllBytes(Path.of(skill.getPath()));
            if (bytes.length > config.getMaxSkillFileSize()) return null;
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Failed to read system skill: {}", skill.getPath(), e);
            return null;
        }
    }

    @Override
    public void beforeModel(CompletionRequest request, ExecutionContext executionContext) {
        if (request == null || request.messages == null || request.messages.isEmpty()) return;
        if (loadedSkills == null || loadedSkills.isEmpty()) return;

        String userQuery = findLastUserMessage(request.messages);
        if (userQuery == null || userQuery.isBlank()) return;

        var matchResult = matchSkills(userQuery);

        if (config.isRecommendEnabled() && !matchResult.taskSkills.isEmpty()) {
            request.messages.add(Message.of(RoleType.SYSTEM, buildRecommendHint(matchResult.taskSkills)));
            LOGGER.debug("Recommended task skills: {}", matchResult.taskSkills);
        }
        if (!matchResult.systemSkills.isEmpty()) {
            request.messages.add(Message.of(RoleType.SYSTEM, buildSystemSkillReminder(matchResult.systemSkills)));
            LOGGER.debug("Triggered system skills: {}", matchResult.systemSkills);
        }
    }

    MatchResult matchSkills(String query) {
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        List<String> taskSkills = new ArrayList<>();
        List<String> systemSkills = new ArrayList<>();
        for (var skill : loadedSkills) {
            if (matchesSkill(skill, lowerQuery)) {
                if (skill.isSystemSkill()) {
                    systemSkills.add(skill.getName());
                } else {
                    taskSkills.add(skill.getName());
                }
            }
        }
        return new MatchResult(taskSkills, systemSkills);
    }

    record MatchResult(List<String> taskSkills, List<String> systemSkills) { }

    private boolean matchesSkill(SkillMetadata skill, String lowerQuery) {
        if (lowerQuery.contains(skill.getName())) {
            return true;
        }
        for (String trigger : skill.getTriggers()) {
            if (lowerQuery.contains(trigger.toLowerCase(Locale.ROOT))) {
                return true;
            }
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

    private String buildRecommendHint(List<String> skillNames) {
        var sb = new StringBuilder(128);
        sb.append("[Skill Recommendation] The following skills may help with this task: ");
        for (int i = 0; i < skillNames.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append('\'').append(skillNames.get(i)).append('\'');
        }
        sb.append(". Use use_skill to load them.");
        return sb.toString();
    }

    private String buildSystemSkillReminder(List<String> skillNames) {
        var sb = new StringBuilder(128);
        sb.append("[System Skill Reminder] The following system skills are triggered: ");
        for (int i = 0; i < skillNames.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append('\'').append(skillNames.get(i)).append('\'');
        }
        sb.append(". Review the <system-skill> instructions and act on them using the relevant tools.");
        return sb.toString();
    }

    public List<SkillMetadata> getLoadedSkills() {
        return loadedSkills == null ? null : Collections.unmodifiableList(loadedSkills);
    }
}
