package ai.core.skill;

import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.llm.domain.CompletionRequest;
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
    }

    @Override
    public void beforeModel(CompletionRequest request, ExecutionContext executionContext) {
        // skill info is now in SkillTool description, no longer injected into system prompt
    }

    public List<SkillMetadata> getLoadedSkills() {
        return loadedSkills == null ? null : Collections.unmodifiableList(loadedSkills);
    }
}
