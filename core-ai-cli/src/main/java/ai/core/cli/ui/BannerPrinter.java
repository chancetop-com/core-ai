package ai.core.cli.ui;

import java.io.PrintWriter;
import java.nio.file.Path;

/**
 * @author xander
 */
public final class BannerPrinter {

    private static final String[] LOGO = {
        "⣠⣶⣶⣄ ⣠⣶⣶⣄ ⣶⣶⣶⣄ ⣶⣶⣶⡀  ⣠⣶⣶⣄ ⣶⡆",
        "⣿⡟⠀⠀ ⣿⡟⢻⣿ ⣿⡟⠻⣷ ⣿⡟⠛⠁  ⣿⣶⣶⣿ ⣿⡇",
        "⠻⣶⣶⠃ ⠻⣶⣶⠃ ⠿⠃ ⠻⠇⠿⠿⠿⠃  ⠿⠃ ⠿ ⠿⠇"
    };

    public static void print(PrintWriter writer, String model) {
        String modelInfo = model != null ? model : "default";
        String cwd = "~/" + Path.of("").toAbsolutePath().getFileName();

        writer.println();
        for (String line : LOGO) {
            writer.println("  " + AnsiTheme.PROMPT + line + AnsiTheme.RESET);
        }
        writer.println("  " + AnsiTheme.MUTED + "v0.1.0 · " + modelInfo
                + " · " + cwd + AnsiTheme.RESET);
        writer.println();
        writer.flush();
    }

    private BannerPrinter() {
    }
}
