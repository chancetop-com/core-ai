package ai.core.utils;

import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * @author stephen
 */
public class ShellUtil {
    private static final Set<String> UNIX_SHELLS = Set.of("zsh", "bash", "sh");
    private static final List<String> WINDOWS_NATIVE_SHELLS = List.of("pwsh.exe", "powershell.exe", "cmd.exe");
    private static final String POWERSHELL_ENCODING_SETUP = "$OutputEncoding=[System.Text.UTF8Encoding]::new();[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new();";
    private static final Map<Platform, String> PREFERRED_SHELLS = new ConcurrentHashMap<>();

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

    public static String execute(List<String> commands, Path workDir) {
        try {
            var process = new ProcessBuilder(commands)
                    .directory(workDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            return readProcessOutput(process);
        } catch (Exception e) {
            return "";
        }
    }

    private static String readProcessOutput(Process process) throws Exception {
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            var sb = new StringBuilder();
            String line = reader.readLine();
            while (line != null) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(line);
                line = reader.readLine();
            }
            process.waitFor(5, TimeUnit.SECONDS);
            return sb.toString().trim();
        }
    }

    /**
     * Resolved once per platform: every lookup shells out to {@code where.exe}/{@code which}, and this sits on the hot
     * path of every shell tool call.
     */
    public static String getPreferredShell(Platform os) {
        return PREFERRED_SHELLS.computeIfAbsent(os, ShellUtil::detectPreferredShell);
    }

    private static String detectPreferredShell(Platform os) {
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

    private static String getFirstExistsShell(Platform os, java.util.Collection<String> shells) {
        return shells.stream().filter(v -> isCommandExists(os, v)).findFirst().orElseThrow();
    }

    private static boolean isUnixShell(String shell) {
        return UNIX_SHELLS.contains(shell);
    }

    public static boolean isPowerShell(String shell) {
        return shell != null && (shell.contains("pwsh") || shell.contains("powershell"));
    }

    /**
     * {@code pwsh} is PowerShell Core (7+); {@code powershell.exe} is Windows PowerShell 5.1. The two differ enough to
     * change what a command should look like — {@code &&}/{@code ||}, ternary and {@code ??}, {@code -AsHashtable},
     * default file encoding, and whether {@code 2>&1} on a native exe poisons {@code $?} — so the tool description has
     * to say which one it is talking to.
     */
    public static boolean isPowerShellCore(String shell) {
        return shell != null && shell.contains("pwsh");
    }

    /**
     * Best-effort shell detection for callers that must not fail, such as building a tool description at class-init
     * time on a platform where no shell lookup makes sense. Returns null when the shell cannot be determined.
     */
    @Nullable
    public static String detectPreferredShellQuietly(Platform os) {
        try {
            return getPreferredShell(os);
        } catch (RuntimeException e) {
            return null;
        }
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

    public static String wrapCommand(String shell, String command) {
        if (command != null && isPowerShell(shell)) {
            return escapePowerShellCommandArgument(POWERSHELL_ENCODING_SETUP + command);
        }
        return command;
    }

    /**
     * Protects double quotes on their way to {@code powershell.exe -Command} / {@code pwsh.exe -Command}.
     *
     * <p>Two layers eat quotes between a Java {@code ProcessBuilder} argument and the script PowerShell finally runs:
     * Windows has no argv, so the JDK flattens the argument list into a single command line, and {@code -Command}
     * then re-parses that command line itself instead of taking the argument verbatim. The result is that every
     * unescaped {@code "} silently disappears — {@code Write-Output "hello world"} reaches PowerShell as
     * {@code Write-Output hello world}, which is why quoted paths, JSON payloads and {@code --pretty=format:"..."}
     * arguments break on Windows.
     *
     * <p>The fix is the MSVC argument convention that both layers understand: double the run of backslashes that
     * precedes a quote, then escape the quote itself with one more backslash. Backslashes not followed by a quote
     * are literal and stay untouched, so Windows paths survive unchanged.
     */
    public static String escapePowerShellCommandArgument(String command) {
        if (command == null || command.indexOf('"') < 0) return command;
        var escaped = new StringBuilder(command.length() + 16);
        int backslashes = 0;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == '\\') {
                backslashes++;
                escaped.append(c);
                continue;
            }
            if (c == '"') {
                escaped.repeat('\\', backslashes + 1);
            }
            backslashes = 0;
            escaped.append(c);
        }
        return escaped.toString();
    }
}
