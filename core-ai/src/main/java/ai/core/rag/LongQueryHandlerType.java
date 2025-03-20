package ai.core.rag;

import core.framework.api.json.Property;

/**
 * @author stephen
 *
 * RAG: use in memory rag to handle long query, usercase is information is too long for llm
 * SUMMARY: use summary to handle long query, usercase is the conversation is too long
 * AGENT: use agent to handle long query, usercase is the conversation cost too much token, use a clean agent to handle it
 */
public enum LongQueryHandlerType {
    @Property(name = "RAG")
    RAG,
    @Property(name = "SUMMARY")
    SUMMARY,
    @Property(name = "AGENT")
    AGENT
}
