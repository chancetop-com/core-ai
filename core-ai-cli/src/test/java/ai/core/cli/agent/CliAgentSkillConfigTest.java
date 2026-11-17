package ai.core.cli.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliAgentSkillConfigTest {

    private static final String RESTRICTED = "core.ai.workspace.restricted";

    private Set<String> names(Path workspace, List<String[]> plugins) {
        return CliAgent.skillConfig(workspace, plugins).getSources().stream()
                .map(source -> source.name())
                .collect(Collectors.toSet());
    }

    @Test
    void restrictedWorkspaceLoadsOnlyUserSkills() {
        System.setProperty(RESTRICTED, "true");
        try {
            var names = names(Path.of("C:/workspace"), List.<String[]>of(new String[]{"legacy", "C:/plugins/legacy/skills"}));
            assertTrue(names.contains("user"));
            assertFalse(names.contains("workspace"));
            assertFalse(names.contains("plugin:legacy"));
        } finally {
            System.clearProperty(RESTRICTED);
        }
    }

    @Test
    void trustedWorkspaceLoadsWorkspaceAndPluginSkills() {
        System.clearProperty(RESTRICTED);
        var names = names(Path.of("C:/workspace"), List.<String[]>of(new String[]{"legacy", "C:/plugins/legacy/skills"}));
        assertTrue(names.contains("user"));
        assertTrue(names.contains("workspace"));
        assertTrue(names.contains("plugin:legacy"));
    }
}
