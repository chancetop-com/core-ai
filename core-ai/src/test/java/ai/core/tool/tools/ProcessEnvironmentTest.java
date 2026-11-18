package ai.core.tool.tools;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessEnvironmentTest {
    @Test
    void shellCommandInheritsTheExecutionContextEnvironment() {
        var windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        var command = windows
                ? List.of("cmd", "/c", "echo %CORE_AI_TEST_ENV%")
                : List.of("sh", "-c", "echo $CORE_AI_TEST_ENV");

        var output = ShellCommandTool.exec(command, System.getProperty("java.io.tmpdir"), 10, null,
                Map.of("CORE_AI_TEST_ENV", "skill-sdk-ok"));

        assertTrue(output.contains("skill-sdk-ok"), output);
    }
}
