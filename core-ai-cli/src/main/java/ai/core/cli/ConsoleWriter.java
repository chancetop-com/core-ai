package ai.core.cli;

/**
 * Simple console writer for startup/early output before TerminalUI is available.
 */
@SuppressWarnings("PMD.SystemPrintln")
public final class ConsoleWriter {

    public static void print(String message) {
        System.out.print(message);
        System.out.flush();
    }

    public static void println(String message) {
        System.out.println(message);
    }

    public static void println() {
        System.out.println();
    }

    public static void printf(String format, Object... args) {
        System.out.printf(format, args);
    }

    public static void printError(String message) {
        System.err.println(message);
    }

    public static void clearLine() {
        System.err.print("\r\033[K");
        System.err.flush();
    }

    public static void clearLineAndPrint(String message) {
        System.err.print("\r\033[K" + message);
        System.err.flush();
    }

    private ConsoleWriter() {
    }
}
