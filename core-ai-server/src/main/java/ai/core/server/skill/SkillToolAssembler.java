package ai.core.server.skill;

import ai.core.server.util.IdLists;
import ai.core.skill.SkillRegistry;
import ai.core.tool.ToolCall;
import ai.core.tool.registry.ToolProvider;
import ai.core.tool.registry.ToolRegistry;
import ai.core.tool.tools.ReadSkillResourceTool;
import core.framework.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registers server-side skill tools into a {@link ToolRegistry}.
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

    public void attach(List<String> skillIds, ToolRegistry toolRegistry) {
        var clean = IdLists.clean(skillIds);
        if (clean.isEmpty()) return;
        toolRegistry.registerProvider(new Provider(mongoSkillProvider, clean, skillService, skillArchiveBuilder));
    }

    private static class Provider implements ToolProvider {
        private final Map<String, ToolCall> tools;

        Provider(MongoSkillProvider mongoSkillProvider, List<String> skillIds,
                 SkillService skillService, SkillArchiveBuilder skillArchiveBuilder) {
            var registry = new SkillRegistry();
            registry.addProvider(mongoSkillProvider.scoped(new java.util.HashSet<>(skillIds)));
            var toolList = List.of(
                    ServerSkillTool.builder()
                            .registry(registry)
                            .skillService(skillService)
                            .archiveBuilder(skillArchiveBuilder)
                            .build(),
                    ReadSkillResourceTool.builder().registry(registry).build());
            var map = new LinkedHashMap<String, ToolCall>();
            for (var tc : toolList) {
                map.put(tc.getName(), tc);
            }
            this.tools = Map.copyOf(map);
        }

        @Override
        public String id() {
            return "server-skill";
        }

        @Override
        public int priority() {
            return 10;
        }

        @Override
        public RefreshPolicy refreshPolicy() {
            return RefreshPolicy.ONCE;
        }

        @Override
        public Map<String, ToolCall> provide() {
            return tools;
        }
    }
}
