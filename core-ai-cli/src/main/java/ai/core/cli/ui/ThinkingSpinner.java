package ai.core.cli.ui;

import java.io.PrintWriter;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Animated spinner with elapsed time display during agent thinking.
 *
 * @author xander
 */
public class ThinkingSpinner {

    private static final char[] BRAILLE_FRAMES = {'⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏'};
    private static final long FRAME_INTERVAL_MS = 80;
    private static final String CLEAR_LINE = "\u001B[2K\r";
    private static final long MESSAGE_INTERVAL_MS = 3000;
    private static final int FALLBACK_WIDTH = 80;
    private static final String[] SPINNER_MESSAGES = {
        "Thinking...",
        "Charging the laser...",
        "Assembling pixels...",
        "Consulting the oracle...",
        "Brewing fresh tokens...",
        "Polishing the output...",
        "Crunching numbers...",
        "Weaving magic...",
        "Summoning results...",
        "Recalibrating flux...",
        "Feeding the hamsters..."
    };

    public static String formatElapsed(long ms) {
        long seconds = ms / 1000;
        if (seconds < 60) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        long secs = seconds % 60;
        return minutes + "m " + secs + "s";
    }

    private final PrintWriter writer;
    private final IntSupplier widthSupplier;
    private volatile Thread thread;
    private volatile long startTime;
    private volatile Supplier<String> statsSupplier;
    private volatile int lastContentLen;

    public ThinkingSpinner(PrintWriter writer) {
        this(writer, () -> FALLBACK_WIDTH);
    }

    public ThinkingSpinner(PrintWriter writer, IntSupplier widthSupplier) {
        this.writer = writer;
        this.widthSupplier = widthSupplier;
    }

    public void setStatsSupplier(Supplier<String> supplier) {
        this.statsSupplier = supplier;
    }

    public void resetTimer() {
        startTime = System.currentTimeMillis();
    }

    public void start() {
        stop();
        if (startTime == 0) {
            startTime = System.currentTimeMillis();
        }
        Thread spinnerThread = new Thread(() -> {
            int frame = 0;
            int prevContentLen = 0;
            while (!Thread.currentThread().isInterrupted()) {
                long elapsedMs = System.currentTimeMillis() - startTime;
                String elapsed = formatElapsed(elapsedMs);
                char spinner = BRAILLE_FRAMES[frame % BRAILLE_FRAMES.length];
                int msgIdx = (int) (elapsedMs / MESSAGE_INTERVAL_MS) % SPINNER_MESSAGES.length;
                String message = SPINNER_MESSAGES[msgIdx];
                Supplier<String> stats = statsSupplier;
                String statsText = "";
                if (stats != null) {
                    String s = stats.get();
                    if (s != null && !s.isEmpty()) {
                        statsText = " | " + s;
                    }
                }
                String content = "  " + spinner + " " + message + " (esc to cancel, " + elapsed + ")" + statsText;
                int termWidth = widthSupplier.getAsInt();
                if (termWidth > 0 && content.length() > termWidth - 1) {
                    content = content.substring(0, termWidth - 1);
                }
                // clear residual wrapped lines from previous frame after terminal shrink
                if (termWidth > 0 && prevContentLen > termWidth) {
                    int extraLines = (prevContentLen - 1) / termWidth;
                    for (int i = 0; i < extraLines; i++) {
                        writer.print("\u001B[A\u001B[2K");
                    }
                }
                writer.print(CLEAR_LINE + AnsiTheme.PROMPT + content + AnsiTheme.RESET);
                writer.flush();
                lastContentLen = content.length();
                prevContentLen = lastContentLen;
                frame++;
                try {
                    Thread.sleep(FRAME_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "thinking-spinner");
        spinnerThread.setDaemon(true);
        thread = spinnerThread;
        spinnerThread.start();
    }

    public void stop() {
        Thread t = thread;
        thread = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        int termWidth = widthSupplier.getAsInt();
        int len = lastContentLen;
        if (termWidth > 0 && len > termWidth) {
            int extraLines = (len - 1) / termWidth;
            for (int i = 0; i < extraLines; i++) {
                writer.print("\u001B[A\u001B[2K");
            }
        }
        lastContentLen = 0;
        writer.print(CLEAR_LINE);
        writer.flush();
    }

    public long getElapsedMs() {
        return startTime > 0 ? System.currentTimeMillis() - startTime : 0;
    }
}
