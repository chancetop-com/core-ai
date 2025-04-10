package ai.core.utils;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * @author stephen
 */
public class ShellUtil {
    public static SystemType getSystemType() {
        var os = System.getProperty("os.name").toLowerCase(Locale.getDefault());
        return SystemType.fromName(os);
    }

    public static boolean isCommandExists(SystemType os, String command) {
        return switch (os) {
            case LIN, MAC -> run(List.of("which", command)) == 0;
            case WIN -> run(List.of("where.exe", command)) == 0;
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

    public static String getPreferredShell(SystemType os) {
        var winPreferredShells = List.of("pwsh.exe", "powershell.exe", "cmd.exe");
        var linPreferredShells = List.of("bash", "zsh", "sh");
        return switch (os) {
            case WIN -> getFirstExistsShell(os, winPreferredShells);
            case LIN -> getFirstExistsShell(os, linPreferredShells);
            default -> throw new RuntimeException("Unsupported OS: " + os);
        };
    }

    private static String getFirstExistsShell(SystemType os, List<String> shells) {
        return shells.stream().filter(v -> isCommandExists(os, v)).findFirst().orElseThrow();
    }

    public static String getPreferredShellCommandPrefix(SystemType os) {
        return switch (os) {
            case LIN, MAC -> getPreferredShell(os) + " -c ";
            case WIN -> getPreferredShell(os) + " -Command ";
            default -> throw new RuntimeException("Unsupported OS: " + os);
        };
    }
}
