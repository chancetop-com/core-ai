package ai.core.prompt;

import java.util.Map;

/**
 * @author stephen
 */
public interface PromptTemplate {
    String execute(String template, Map<String, Object> scopes, String name);
}
