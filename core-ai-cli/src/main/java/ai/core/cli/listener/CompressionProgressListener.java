package ai.core.cli.listener;

import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.OutputPanel;
import ai.core.cli.ui.TerminalUI;
import ai.core.context.CompressionListener;
import ai.core.context.CompressionReport;

import java.util.Locale;

/**
 * Renders compression progress in the CLI so a started compression is always resolved: either the
 * summarized message counts with the context usage that triggered it, or the reason the conversation
 * was kept as it was.
 *
 * @author stephen
 */
public class CompressionProgressListener implements CompressionListener {
    private final OutputPanel panel;
    private final TerminalUI ui;

    public CompressionProgressListener(OutputPanel panel, TerminalUI ui) {
        this.panel = panel;
        this.ui = ui;
    }

    @Override
    public void onCompression(CompressionReport report) {
        switch (report.phase()) {
            case STARTED -> print("\n  " + AnsiTheme.MUTED + "\u2726 Compressing " + report.afterCount() + " messages... "
                + context(report) + AnsiTheme.RESET);
            case COMPLETED -> print("\n  " + AnsiTheme.SUCCESS + "\u2726" + AnsiTheme.RESET + AnsiTheme.MUTED
                + " Compressed: " + report.beforeCount() + " \u2192 " + report.afterCount() + " messages "
                + context(report) + AnsiTheme.RESET + "\n");
            case SKIPPED -> print("\n  " + AnsiTheme.MUTED + "\u2726 Compression skipped: " + report.reason() + AnsiTheme.RESET + "\n");
            default -> throw new IllegalStateException("unknown compression phase: " + report.phase());
        }
    }

    private String context(CompressionReport report) {
        return String.format(Locale.ROOT, "(%,d / %,d tokens, %d%%, threshold %d%%)",
            report.contextTokens(), report.maxContextTokens(), report.usedPercent(), report.thresholdPercent());
    }

    private void print(String message) {
        boolean wasSpinning = panel.stopSpinnerIfActive();
        ui.printStreamingChunk(message);
        // only resume the spinner of a running turn; outside a turn (e.g. /compact) it would
        // never be stopped again before the input prompt is redrawn
        if (wasSpinning) {
            panel.startSpinner();
        }
    }
}
