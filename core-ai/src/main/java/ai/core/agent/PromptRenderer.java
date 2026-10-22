package ai.core.agent;

import ai.core.prompt.engines.MustachePromptTemplate;
import core.framework.crypto.Hash;

import java.util.HashMap;
import java.util.Map;

/**
 * Renders the text the agent sends to the model. Only author-written templates (the system prompt, the
 * prompt template, the RAG context template) go through Mustache; runtime data (prompt sections, the user
 * query, a reflection prompt built from model output) is attached verbatim, so a stray "{{" written by a
 * model or a user can never fail the turn.
 *
 * @author Xander
 */
final class PromptRenderer {
    static String renderSystem(String template, String appendix, Map<String, Object> variables, Map<String, Object> systemVariables) {
        Map<String, Object> scopes = new HashMap<>();
        if (variables != null) scopes.putAll(variables);
        if (systemVariables != null) scopes.putAll(systemVariables);
        var rendered = render(template, scopes);
        if (appendix == null || appendix.isBlank()) return rendered;
        if (rendered.isBlank()) return appendix.strip();
        return rendered + "\n\n" + appendix.stripLeading();
    }

    static String renderUserTurn(String promptTemplate, String query, String ragTemplate, Map<String, Object> scopes) {
        return render(promptTemplate, scopes) + (query == null ? "" : query) + render(ragTemplate, scopes);
    }

    private static String render(String template, Map<String, Object> scopes) {
        if (template == null || template.isEmpty()) return "";
        return new MustachePromptTemplate().execute(template, scopes, Hash.md5Hex(template));
    }

    private PromptRenderer() {
    }
}
