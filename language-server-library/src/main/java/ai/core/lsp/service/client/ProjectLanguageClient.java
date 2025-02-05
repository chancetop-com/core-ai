package ai.core.lsp.service.client;

import org.eclipse.lsp4j.ConfigurationParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.services.LanguageClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * @author stephen
 */
public class ProjectLanguageClient implements LanguageClient {
    private final Logger logger = LoggerFactory.getLogger(ProjectLanguageClient.class);

    @Override
    public void telemetryEvent(Object o) {
        logger.info("telemetryEvent: {}", o);
    }

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams publishDiagnosticsParams) {
        logger.info("publishDiagnostics: {}", publishDiagnosticsParams);
    }

    @Override
    public CompletableFuture<List<Object>> configuration(ConfigurationParams configurationParams) {
        logger.info("configuration: {}", configurationParams);
        return CompletableFuture.completedFuture(Collections.emptyList());
    }

    @Override
    public void showMessage(MessageParams messageParams) {
        logger.info("showMessage: {}", messageParams);
    }

    @Override
    public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams showMessageRequestParams) {
        logger.info("showMessageRequest: {}", showMessageRequestParams);
        return CompletableFuture.completedFuture(new MessageActionItem(""));
    }

    @Override
    public void logMessage(MessageParams messageParams) {
        logger.info("logMessage: {}", messageParams);
    }
}
