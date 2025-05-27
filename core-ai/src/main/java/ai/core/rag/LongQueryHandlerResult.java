package ai.core.rag;

import ai.core.llm.domain.Usage;

/**
 * @author stephen
 */
public record LongQueryHandlerResult(String shorterQuery, Usage usage) {
}
