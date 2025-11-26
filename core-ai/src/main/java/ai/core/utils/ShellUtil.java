package ai.core.utils;

import java.io.IOException;
import java.util.List;

/**
 * @author stephen
 */
public class ShellUtil {
    public static boolean isCommandExists(Platform os, String command) {
        return switch (os) {
            case LINUX_X64, MACOS_X64 -> run(List.of("which", command)) == 0;
            case WINDOWS_X64 -> run(List.of("where.exe", command)) == 0;
            default -> false;
        };
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
        var winPreferredShells = List.of("pwsh.exe", "powershell.exe", "cmd.exe");
        var unixPreferredShells = List.of("bash", "zsh", "sh");
        return switch (os) {
            case WINDOWS_X64 -> getFirstExistsShell(os, winPreferredShells);
            case LINUX_X64, MACOS_X64 -> getFirstExistsShell(os, unixPreferredShells);
            default -> throw new RuntimeException("Unsupported OS: " + os);
        };
    }

    private static String getFirstExistsShell(Platform os, List<String> shells) {
        return shells.stream().filter(v -> isCommandExists(os, v)).findFirst().orElseThrow();
    }

    public static String getPreferredShellCommandPrefix(Platform os) {
        return switch (os) {
            case LINUX_X64, MACOS_X64 -> getPreferredShell(os) + " -c ";
            case WINDOWS_X64 -> getPreferredShell(os) + " -Command ";
            default -> throw new RuntimeException("Unsupported OS: " + os);
        };
    }
}
