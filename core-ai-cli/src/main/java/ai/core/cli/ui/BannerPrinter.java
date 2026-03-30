package ai.core.cli.ui;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * @author xander
 */
public final class BannerPrinter {

    private static final String[] LOGO = {
        "⣠⣶⣶⣄ ⣠⣶⣶⣄ ⣶⣶⣶⣄ ⣶⣶⣶⡀  ⣠⣶⣶⣄ ⣶⡆",
        "⣿⡟⠀⠀ ⣿⡟⢻⣿ ⣿⡟⠻⣷ ⣿⡟⠛⠁  ⣿⣶⣶⣿ ⣿⡇",
        "⠻⣶⣶⠃ ⠻⣶⣶⠃ ⠿⠃ ⠻⠇⠿⠿⠿⠃  ⠿⠃ ⠿ ⠿⠇"
    };

    private static final Path CONFIG_FILE = Path.of(System.getProperty("user.home"), ".core-ai", "agent.properties");

    public static void print(PrintWriter writer, String model) {
        String modelInfo = model != null ? model : "default";
        String cwd = "~/" + Path.of("").toAbsolutePath().getFileName();
        String username = readUsername();

        writer.println();
        for (String line : LOGO) {
            writer.println("  " + AnsiTheme.PROMPT + line + AnsiTheme.RESET);
        }
        String userTag = username != null
                ? AnsiTheme.SUCCESS + username + AnsiTheme.RESET + AnsiTheme.MUTED + " · " : "";
        writer.println("  " + AnsiTheme.MUTED + "v0.1.0 · " + userTag + modelInfo
                + " · " + cwd + AnsiTheme.RESET);
        writer.println();
        writer.flush();
    }

    private static String readUsername() {
        if (!Files.exists(CONFIG_FILE)) return null;
        var props = new Properties();
        try (var is = Files.newInputStream(CONFIG_FILE)) {
            props.load(is);
        } catch (IOException e) {
            return null;
        }
        String value = props.getProperty("username");
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    private BannerPrinter() {
    }
}
