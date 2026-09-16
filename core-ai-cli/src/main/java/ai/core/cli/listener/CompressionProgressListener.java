package ai.core.cli.listener;

import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.OutputPanel;
import ai.core.cli.ui.TerminalUI;
import ai.core.context.CompressionListener;

/**
 * Renders compression progress in the CLI so a started compression is always resolved: either the
 * summarized message counts, or the reason the conversation was kept as it was.
 *
 * @author xander
 */
public class CompressionProgressListener implements CompressionListener {
    private final OutputPanel panel;
    private final TerminalUI ui;

    public CompressionProgressListener(OutputPanel panel, TerminalUI ui) {
        this.panel = panel;
        this.ui = ui;
    }

    @Override
    public void onCompression(int beforeCount, int afterCount, boolean completed) {
        if (completed) {
            print("\n  " + AnsiTheme.SUCCESS + "\u2726" + AnsiTheme.RESET + AnsiTheme.MUTED
                + " Compressed: " + beforeCount + " \u2192 " + afterCount + " messages" + AnsiTheme.RESET + "\n");
        } else {
            print("\n  " + AnsiTheme.MUTED + "\u2726 Compressing " + afterCount + " messages..." + AnsiTheme.RESET);
        }
    }

    @Override
    public void onCompressionSkipped(int beforeCount, String reason) {
        print("\n  " + AnsiTheme.MUTED + "\u2726 Compression skipped: " + reason + AnsiTheme.RESET + "\n");
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
