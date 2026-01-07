package ai.core.compression;

import ai.core.agent.ExecutionContext;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * @author xander
 */
public class CompressionLifecycle extends AbstractLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger(CompressionLifecycle.class);

    private final Compression compression;

    public CompressionLifecycle(Compression compression) {
        this.compression = compression;
    }

    @Override
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    public void beforeModel(CompletionRequest request, ExecutionContext executionContext) {
        if (request == null || request.messages == null) {
            return;
        }

        List<Message> compressed = compression.compress(request.messages);
        if (compressed != request.messages) {
            request.messages = compressed;
            LOGGER.debug("Messages compressed before model call");
        }
    }
}
