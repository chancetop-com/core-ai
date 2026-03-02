package ai.core.cli.ui;

import java.io.PrintWriter;

/**
 * @author stephen
 */
public final class BannerPrinter {
    private BannerPrinter() {
    }

    private static final String[] BANNER_LINES = {
            "  ██████╗ ██████╗ ██████╗ ███████╗     █████╗ ██╗",
            " ██╔════╝██╔═══██╗██╔══██╗██╔════╝    ██╔══██╗██║",
            " ██║     ██║   ██║██████╔╝█████╗      ███████║██║",
            " ██║     ██║   ██║██╔══██╗██╔══╝      ██╔══██║██║",
            " ╚██████╗╚██████╔╝██║  ██║███████╗    ██║  ██║██║",
            "  ╚═════╝ ╚═════╝ ╚═╝  ╚═╝╚══════╝    ╚═╝  ╚═╝╚═╝"
    };

    // ANSI 256-color codes for orange gradient (dark orange -> orange -> bright orange/yellow)
    private static final int[] GRADIENT_COLORS = {166, 172, 208, 214, 220, 221};

    public static void print(PrintWriter writer, int terminalWidth, String model) {
        writer.println();

        for (int i = 0; i < BANNER_LINES.length; i++) {
            int colorCode = GRADIENT_COLORS[i % GRADIENT_COLORS.length];
            String line = BANNER_LINES[i];
            int padding = Math.max(0, (terminalWidth - visibleLength(line)) / 2);
            writer.println(" ".repeat(padding) + "\u001B[38;5;" + colorCode + "m" + line + "\u001B[0m");
        }

        writer.println();

        String info = "v0.1.0 | model: " + (model != null ? model : "default");
        int infoPadding = Math.max(0, (terminalWidth - info.length()) / 2);
        writer.println(" ".repeat(infoPadding) + "\u001B[90m" + info + "\u001B[0m");

        String help = "Type '/help' for commands, '/exit' to quit.";
        int helpPadding = Math.max(0, (terminalWidth - help.length()) / 2);
        writer.println(" ".repeat(helpPadding) + "\u001B[90m" + help + "\u001B[0m");

        writer.println();
        writer.flush();
    }

    private static int visibleLength(String s) {
        // strip ANSI escape sequences for length calculation
        return s.replaceAll("\u001B\\[[;\\d]*m", "").length();
    }
}
