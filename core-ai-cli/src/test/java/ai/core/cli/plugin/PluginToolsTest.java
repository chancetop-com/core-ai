package ai.core.cli.plugin;

import ai.core.agent.ExecutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PluginToolsTest {
    @TempDir
    Path workspace;

    @Test
    void parsesOnlySupportedReadOnlyTools() {
        var tools = PluginTools.parse("""
                [{"name":"plugin_demo_list","description":"list","operation":"artifacts.list","limit":10},
                 {"name":"bad","description":"x","operation":"shell.exec"},
                 {"name":"bad name","description":"x","operation":"memory.list"}]
                """);
        assertThat(tools).hasSize(1);
        assertThat(tools.getFirst().getName()).isEqualTo("plugin_demo_list");
    }

    @Test
    void executesArtifactsListAgainstTheWorkspace() throws Exception {
        Files.createDirectories(workspace.resolve(".core-ai/media/images"));
        Files.writeString(workspace.resolve(".core-ai/media/images/one.png"), "png");
        var tool = PluginTools.parse("[{\"name\":\"plugin_demo_list\",\"description\":\"list\",\"operation\":\"artifacts.list\"}]").getFirst();
        var context = ExecutionContext.builder().customVariables(Map.of("workspace", workspace.toString())).build();
        var result = tool.execute("{}", context);
        assertThat(result.isCompleted()).isTrue();
        assertThat(result.getResult()).contains("one.png");
    }
}
