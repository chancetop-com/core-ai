package ai.core.cli.hub;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards the CLI hub architecture invariant: {@code ai.core.cli.hub} is a pure
 * HTTP + rendering client — it must never drag in the agent/LLM/MCP stacks that the
 * local REPL depends on (cold start and isolation).
 */
class HubPackageBoundaryTest {
    private static final List<String> FORBIDDEN_PREFIXES = List.of(
            "import ai.core.cli.CliApp",
            "import ai.core.cli.BootstrapCore",
            "import ai.core.bootstrap.",
            "import ai.core.mcp.",
            "import ai.core.llm.",
            "import ai.core.agent.",
            "import ai.core.cli.acp.");

    @Test
    void hubPackageDoesNotImportAgentStack() throws IOException {
        var hubDir = Path.of("src/main/java/ai/core/cli/hub");
        if (!Files.isDirectory(hubDir)) fail("hub package not found: " + hubDir.toAbsolutePath());
        try (Stream<Path> files = Files.walk(hubDir)) {
            var violations = files.filter(file -> file.toString().endsWith(".java"))
                    .flatMap(file -> readImports(file).stream()
                            .filter(line -> FORBIDDEN_PREFIXES.stream().anyMatch(line::startsWith))
                            .map(line -> file.getFileName() + ": " + line))
                    .toList();
            assertEquals(List.of(), violations, "hub package must not import agent stack");
        }
    }

    private List<String> readImports(Path file) {
        try {
            return Files.readAllLines(file).stream().filter(line -> line.startsWith("import ")).toList();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read " + file, e);
        }
    }
}
