package ai.core.cli.ui;

import java.io.PrintWriter;

/**
 * @author xander
 */
public final class BannerPrinter {
    private static final String[] BANNER_LINES = {
        "  ██████╗ ██████╗ ██████╗ ███████╗     █████╗ ██╗",
        " ██╔════╝██╔═══██╗██╔══██╗██╔════╝    ██╔══██╗██║",
        " ██║     ██║   ██║██████╔╝█████╗      ███████║██║",
        " ██║     ██║   ██║██╔══██╗██╔══╝      ██╔══██║██║",
        " ╚██████╗╚██████╔╝██║  ██║███████╗    ██║  ██║██║",
        "  ╚═════╝ ╚═════╝ ╚═╝  ╚═╝╚══════╝    ╚═╝  ╚═╝╚═╝"
    };

    private static final int[] GRADIENT_COLORS = {166, 172, 208, 214, 220, 221};

    private static final int MAX_CENTER_WIDTH = 120;

    public static void print(PrintWriter writer, int terminalWidth, String model) {
        int width = Math.min(terminalWidth, MAX_CENTER_WIDTH);
        writer.println();

        for (int i = 0; i < BANNER_LINES.length; i++) {
            int colorCode = GRADIENT_COLORS[i % GRADIENT_COLORS.length];
            String line = BANNER_LINES[i];
            int padding = Math.max(0, (width - visibleLength(line)) / 2);
            writer.println(" ".repeat(padding) + "\u001B[38;5;" + colorCode + "m" + line + AnsiTheme.RESET);
        }

        writer.println();

        String info = "v0.1.0 | model: " + (model != null ? model : "default");
        int infoPadding = Math.max(0, (width - info.length()) / 2);
        writer.println(" ".repeat(infoPadding) + AnsiTheme.MUTED + info + AnsiTheme.RESET);

        String help = "Type '/help' for commands, '/exit' to quit.";
        int helpPadding = Math.max(0, (width - help.length()) / 2);
        writer.println(" ".repeat(helpPadding) + AnsiTheme.MUTED + help + AnsiTheme.RESET);

        writer.println();
        writer.flush();
    }

    private static int visibleLength(String s) {
        return s.replaceAll("\u001B\\[[;\\d]*m", "").length();
    }

    private BannerPrinter() {
    }
}
