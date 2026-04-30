package ai.core.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author stephen
 */
class ShellUtilTest {

    @Test
    void getPreferredShellOnUnixReturnsBashOrFallback() {
        var shell = ShellUtil.getPreferredShell(Platform.LINUX_X64);
        assertTrue(shell.equals("bash") || shell.equals("zsh") || shell.equals("sh"),
                "Unix preferred shell must be one of bash/zsh/sh, but got: " + shell);
    }

    @Test
    void getPreferredShellOnMacReturnsBashOrFallback() {
        var shell = ShellUtil.getPreferredShell(Platform.MACOS_X64);
        assertTrue(shell.equals("bash") || shell.equals("zsh") || shell.equals("sh"),
                "Mac preferred shell must be one of bash/zsh/sh, but got: " + shell);
    }

    @Test
    void getPreferredShellOnWindowsReturnsUnixShellWhenAvailable() {
        var shell = ShellUtil.getPreferredShell(Platform.WINDOWS_X64);
        // If bash/zsh/sh are available on this Windows system, they should be preferred
        // Otherwise fall back to pwsh/powershell/cmd
        var validShells = java.util.List.of("bash", "zsh", "sh", "pwsh.exe", "powershell.exe", "cmd.exe");
        assertTrue(validShells.contains(shell),
                "Windows preferred shell must be one of " + validShells + ", but got: " + shell);
    }

    @Test
    void getPreferredShellCommandPrefixOnUnixUsesDashC() {
        var prefix = ShellUtil.getPreferredShellCommandPrefix(Platform.LINUX_X64);
        assertTrue(prefix.endsWith(" -c "),
                "Unix shell prefix must end with ' -c ', but got: " + prefix);
    }

    @Test
    void getPreferredShellCommandPrefixOnMacUsesDashC() {
        var prefix = ShellUtil.getPreferredShellCommandPrefix(Platform.MACOS_X64);
        assertTrue(prefix.endsWith(" -c "),
                "Mac shell prefix must end with ' -c ', but got: " + prefix);
    }

    @Test
    void getPreferredShellCommandPrefixOnWindowsUsesCorrectFlag() {
        var shell = ShellUtil.getPreferredShell(Platform.WINDOWS_X64);
        var prefix = ShellUtil.getPreferredShellCommandPrefix(Platform.WINDOWS_X64);
        if (java.util.List.of("bash", "zsh", "sh").contains(shell)) {
            assertTrue(prefix.endsWith(" -c "),
                    "Windows shell prefix for " + shell + " must end with ' -c ', but got: " + prefix);
        } else {
            assertTrue(prefix.endsWith(" -Command "),
                    "Windows shell prefix for " + shell + " must end with ' -Command ', but got: " + prefix);
        }
    }

    @Test
    void isCommandExistsOnWindowsHandlesKnownCommands() {
        // cmd.exe should always exist on Windows
        assertTrue(ShellUtil.isCommandExists(Platform.WINDOWS_X64, "cmd.exe"));
    }

    @Test
    void isCommandExistsOnUnixReturnsFalseForUnknownCommand() {
        assertFalse(ShellUtil.isCommandExists(Platform.LINUX_X64, "thiscommanddoesnotexistxyz"));
    }

    @Test
    void unsupportedPlatformThrows() {
        assertThrows(RuntimeException.class, () -> ShellUtil.getPreferredShell(Platform.UNKNOWN));
        assertThrows(RuntimeException.class, () -> ShellUtil.getPreferredShellCommandPrefix(Platform.UNKNOWN));
    }
}
