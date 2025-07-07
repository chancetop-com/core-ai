package ai.core.defaultagents;

import ai.core.agent.Agent;
import ai.core.llm.LLMProvider;

/**
 * @author stephen
 */
public class DefaultRagQueryRewriteAgent {
    public static Agent of(LLMProvider llmProvider) {
        return Agent.builder()
                .name("rag-query-rewrite-agent")
                .description("A default agent for rewriting RAG queries using LLMs.")
                .systemPrompt("""
                        Given a conversation history and a follow-up question, rephrase the follow-up question to be a standalone question that captures all relevant context from the history.
                        
                        The standalone question should be in the same language as the follow-up question.
                        
                        ---
                        
                        **Conversation History:**
                        {{{chat_history}}}
                        
                        **Follow-up Question:**
                        {{{input}}}
                        
                        **Standalone Question:**
                        """)
                .llmProvider(llmProvider).build();
    }
}
