package ai.core.server.skill;

import ai.core.server.util.IdLists;
import ai.core.skill.SkillRegistry;
import ai.core.tool.ToolCall;
import ai.core.tool.tools.ReadSkillResourceTool;
import core.framework.inject.Inject;

import java.util.HashSet;
import java.util.List;

/**
 * Builds a SkillRegistry scoped to the given skill ids and appends the matching skill tools to a tool list.
 * Shared by the run path, the session path and sub-agent assembly so skill wiring stays identical everywhere.
 *
 * @author Xander
 */
public class SkillToolAssembler {
    @Inject
    MongoSkillProvider mongoSkillProvider;
    @Inject
    SkillService skillService;
    @Inject
    SkillArchiveBuilder skillArchiveBuilder;

    /**
     * Appends ServerSkillTool + ReadSkillResourceTool (scoped to skillIds) to {@code tools} and returns the registry.
     * Returns null when skillIds is empty, so callers can skip {@code builder.skillRegistry(...)}.
     */
    public SkillRegistry attach(List<String> skillIds, List<ToolCall> tools) {
        var clean = IdLists.clean(skillIds);
        if (clean.isEmpty()) return null;
        var registry = new SkillRegistry();
        registry.addProvider(mongoSkillProvider.scoped(new HashSet<>(clean)));
        tools.add(ServerSkillTool.builder()
                .registry(registry)
                .skillService(skillService)
                .archiveBuilder(skillArchiveBuilder)
                .build());
        tools.add(ReadSkillResourceTool.builder().registry(registry).build());
        return registry;
    }
}
