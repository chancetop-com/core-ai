package ai.core.rag;

import ai.core.llm.providers.inner.Usage;

/**
 * @author stephen
 */
public record LongQueryHandlerResult(String shorterQuery, Usage usage) {
}
