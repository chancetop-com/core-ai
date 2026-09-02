package ai.core.tool.tools;

import ai.core.utils.ShellUtil;
import ai.core.utils.SystemUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * @author stephen
 */
class PowershellCommandToolTest {
    private PowershellCommandTool tool;

    @BeforeEach
    void setUp() {
        tool = PowershellCommandTool.builder().build();
    }

    @Test
    void shouldBuildCorrectly() {
        assertNotNull(tool);
        assertEquals("run_bash_command", tool.getName());
        assertNotNull(tool.getDescription());
    }

    @Test
    void shouldResolveEveryDescriptionPlaceholder() {
        assertFalse(tool.getDescription().contains("${"), "tool description still has an unresolved placeholder");
    }

    @Test
    void shouldTellAgentNotToPreEscapeQuotes() {
        var description = tool.getDescription();
        assertTrue(description.contains("Double quotes are passed through verbatim"));
        assertTrue(description.contains("Do NOT pre-escape quotes"));
    }

    @Test
    void shouldDescribeTheEditionThatCommandsActuallyRunOn() {
        assumeTrue(SystemUtil.detectPlatform().isWindows());
        var shell = ShellUtil.detectPreferredShellQuietly(SystemUtil.detectPlatform());
        assumeTrue(ShellUtil.isPowerShell(shell), "preferred shell is not PowerShell, but got: " + shell);
        var description = tool.getDescription();

        if (ShellUtil.isPowerShellCore(shell)) {
            assertTrue(description.contains("PowerShell edition: PowerShell 7+ (pwsh)"));
            assertTrue(description.contains("`&&` and `||` ARE available"));
            assertTrue(description.contains("`-AsHashtable` when you want a hashtable"));
            assertFalse(description.contains("are NOT available"), "PowerShell 7 must not be told 5.1 syntax limits");
        } else {
            assertTrue(description.contains("PowerShell edition: Windows PowerShell 5.1"));
            assertTrue(description.contains("`&&` and `||` are NOT available"));
            assertTrue(description.contains("`-AsHashtable` is not available"));
        }
    }
}
