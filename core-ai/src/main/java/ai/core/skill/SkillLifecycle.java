package ai.core.skill;

import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.Content;
import ai.core.llm.domain.RoleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

/**
 * @author xander
 */
public class SkillLifecycle extends AbstractLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkillLifecycle.class);

    private final SkillConfig config;
    private final SkillLoader loader;
    private final SkillPromptFormatter formatter;
    private volatile List<SkillMetadata> loadedSkills;
    private volatile String cachedSkillsSection;

    public SkillLifecycle(SkillConfig config) {
        this.config = config;
        this.loader = new SkillLoader(config.getMaxSkillFileSize());
        this.formatter = new SkillPromptFormatter();
    }

    @Override
    public void afterAgentBuild(Agent agent) {
        if (!config.isEnabled()) return;
        this.loadedSkills = loader.loadAll(config.getSources());
        this.cachedSkillsSection = formatter.format(loadedSkills);
        LOGGER.debug("Loaded {} skills", loadedSkills.size());
    }

    @Override
    public void beforeModel(CompletionRequest request, ExecutionContext executionContext) {
        if (cachedSkillsSection == null || cachedSkillsSection.isEmpty()) return;
        if (request == null || request.messages == null || request.messages.isEmpty()) return;

        appendToSystemMessage(request, cachedSkillsSection);
    }

    private void appendToSystemMessage(CompletionRequest request, String skillsSection) {
        for (var message : request.messages) {
            if (message.role == RoleType.SYSTEM) {
                String currentText = message.getTextContent();
                if (currentText == null) currentText = "";
                message.content = List.of(Content.of(currentText + skillsSection));
                return;
            }
        }
        LOGGER.debug("No system message found in request, skills section not injected");
    }

    public List<SkillMetadata> getLoadedSkills() {
        return loadedSkills == null ? null : Collections.unmodifiableList(loadedSkills);
    }
}
