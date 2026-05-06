package ai.core.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author stephen
 */
final class BrowserLauncher {
    private static final Logger LOGGER = LoggerFactory.getLogger(BrowserLauncher.class);

    static void open(String url) {
        try {
            var os = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT);
            ProcessBuilder pb;
            if (os.contains("win")) {
                pb = new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url);
            } else if (os.contains("mac")) {
                pb = new ProcessBuilder("open", url);
            } else {
                pb = new ProcessBuilder("xdg-open", url);
            }
            pb.start();
        } catch (Exception e) {
            LOGGER.warn("failed to open browser: {}", e.getMessage());
        }
    }

    private BrowserLauncher() {
    }
}
