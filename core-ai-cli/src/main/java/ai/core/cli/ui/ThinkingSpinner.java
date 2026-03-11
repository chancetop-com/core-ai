package ai.core.cli.ui;

import java.io.IOError;
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
        Thread spinnerThread = new Thread(this::runSpinnerLoop, "thinking-spinner");
        spinnerThread.setDaemon(true);
        thread = spinnerThread;
        spinnerThread.start();
    }

    private void runSpinnerLoop() {
        int frame = 0;
        int prevContentLen = 0;
        while (!Thread.currentThread().isInterrupted()) {
            String content = buildSpinnerContent(frame);
            int termWidth = safeGetTermWidth();
            if (termWidth < 0) break;
            if (termWidth > 0 && content.length() > termWidth - 1) {
                content = content.substring(0, termWidth - 1);
            }
            clearWrappedLines(termWidth, prevContentLen);
            int pad = Math.max(0, prevContentLen - content.length());
            writer.print("\r" + AnsiTheme.PROMPT + content + AnsiTheme.RESET + " ".repeat(pad));
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
    }

    private String buildSpinnerContent(int frame) {
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
        return "  " + spinner + " " + message + " (esc to cancel, " + elapsed + ")" + statsText;
    }

    private int safeGetTermWidth() {
        try {
            return widthSupplier.getAsInt();
        } catch (Exception | IOError e) {
            return -1;
        }
    }

    private void clearWrappedLines(int termWidth, int prevContentLen) {
        if (termWidth > 0 && prevContentLen > termWidth) {
            int extraLines = (prevContentLen - 1) / termWidth;
            for (int i = 0; i < extraLines; i++) {
                writer.print("\u001B[A\u001B[2K");
            }
        }
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
        int termWidth = safeGetTermWidth();
        if (termWidth > 0) {
            clearWrappedLines(termWidth, lastContentLen);
        }
        writer.print("\r" + " ".repeat(lastContentLen) + "\r");
        writer.flush();
        lastContentLen = 0;
    }

    public long getElapsedMs() {
        return startTime > 0 ? System.currentTimeMillis() - startTime : 0;
    }
}
