package ai.core.cli.ui;

import java.io.PrintWriter;

/**
 * Animated spinner with elapsed time display during agent thinking.
 *
 * @author xander
 */
public class ThinkingSpinner {

    private static final char[] BRAILLE_FRAMES = {'⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏'};
    private static final long FRAME_INTERVAL_MS = 80;
    private static final String CLEAR_LINE = "\u001B[2K\r";

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
    private volatile Thread thread;
    private volatile long startTime;

    public ThinkingSpinner(PrintWriter writer) {
        this.writer = writer;
    }

    public void start() {
        stop();
        startTime = System.currentTimeMillis();
        Thread spinnerThread = new Thread(() -> {
            int frame = 0;
            while (!Thread.currentThread().isInterrupted()) {
                String elapsed = formatElapsed(System.currentTimeMillis() - startTime);
                char spinner = BRAILLE_FRAMES[frame % BRAILLE_FRAMES.length];
                writer.print(CLEAR_LINE + "  " + AnsiTheme.PROMPT + spinner + " " + AnsiTheme.MUTED + "Thinking... "
                        + AnsiTheme.SEPARATOR + "(" + elapsed + ")" + AnsiTheme.RESET);
                writer.flush();
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
        writer.print(CLEAR_LINE);
        writer.flush();
    }

    public long getElapsedMs() {
        return startTime > 0 ? System.currentTimeMillis() - startTime : 0;
    }
}
