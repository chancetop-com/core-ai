package ai.core.utils;

import java.io.IOException;
import java.util.List;

/**
 * @author stephen
 */
public class ShellUtil {
    private static final List<String> UNIX_SHELLS = List.of("zsh", "bash", "sh");
    private static final List<String> WINDOWS_NATIVE_SHELLS = List.of("pwsh.exe", "powershell.exe", "cmd.exe");

    public static boolean isCommandExists(Platform os, String command) {
        try {
            return switch (os) {
                case LINUX_X64, MACOS_X64 -> run(List.of("which", command)) == 0;
                case WINDOWS_X64 -> run(List.of("where.exe", command)) == 0;
                default -> false;
            };
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static int run(List<String> commands) {
        try {
            var process = Runtime.getRuntime().exec(commands.toArray(String[]::new));
            return process.waitFor();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static String getPreferredShell(Platform os) {
        return switch (os) {
            case WINDOWS_X64 -> getWindowsPreferredShell();
            case LINUX_X64, MACOS_X64 -> getFirstExistsShell(os, UNIX_SHELLS);
            default -> throw new RuntimeException("Unsupported OS: " + os);
        };
    }

    private static String getWindowsPreferredShell() {
//        // Prefer Unix-like shells (Git Bash, MSYS2, Cygwin, WSL) on Windows
//        // when available, then fall back to Windows native shells.
//        for (var shell : UNIX_SHELLS) {
//            if (isCommandExists(Platform.WINDOWS_X64, shell)) {
//                return shell;
//            }
//        }
        return getFirstExistsShell(Platform.WINDOWS_X64, WINDOWS_NATIVE_SHELLS);
    }

    private static String getFirstExistsShell(Platform os, List<String> shells) {
        return shells.stream().filter(v -> isCommandExists(os, v)).findFirst().orElseThrow();
    }

    private static boolean isUnixShell(String shell) {
        return UNIX_SHELLS.contains(shell);
    }

    public static boolean isPowerShell(String shell) {
        return shell != null && (shell.contains("pwsh") || shell.contains("powershell"));
    }

    public static String getPreferredShellCommandPrefix(Platform os) {
        var shell = getPreferredShell(os);
        if (isUnixShell(shell)) {
            return shell + " -c ";
        }
        if (isPowerShell(shell)) {
            return shell + " -NoProfile -Command ";
        }
        return shell + " /c ";
    }

    public static String getPowerShellEncodingSetup() {
        return "$OutputEncoding=[System.Text.UTF8Encoding]::new();[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new();";
    }

    public static String wrapCommand(String shell, String command) {
        if (isPowerShell(shell) && command != null) {
            return getPowerShellEncodingSetup() + command;
        }
        return command;
    }
}
