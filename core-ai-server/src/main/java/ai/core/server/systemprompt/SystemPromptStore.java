package ai.core.server.systemprompt;

import ai.core.server.domain.SystemPrompt;

import java.util.List;

/**
 * @author stephen
 */
public interface SystemPromptStore {
    void insert(SystemPrompt prompt);

    SystemPrompt latestByPromptId(String promptId);

    SystemPrompt latestByName(String name);

    List<SystemPrompt> listAll();

    List<SystemPrompt> versions(String promptId);

    SystemPrompt version(String promptId, int version);

    void deleteByPromptId(String promptId);
}
