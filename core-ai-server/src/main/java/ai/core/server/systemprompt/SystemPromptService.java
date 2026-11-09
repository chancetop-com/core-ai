package ai.core.server.systemprompt;

import ai.core.api.server.systemprompt.SystemPromptRequest;
import ai.core.api.server.systemprompt.SystemPromptTestRequest;
import ai.core.api.server.systemprompt.SystemPromptTestResponse;
import ai.core.api.server.systemprompt.SystemPromptVersionView;
import ai.core.api.server.systemprompt.SystemPromptView;
import ai.core.llm.LLMProviders;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.server.domain.SystemPrompt;
import core.framework.inject.Inject;
import core.framework.web.exception.NotFoundException;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Xander
 */
public class SystemPromptService {
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{(\\w+)}}");

    @Inject
    SystemPromptStore store;

    @Inject
    LLMProviders llmProviders;

    public List<SystemPromptView> list(int offset, int limit) {
        var promptIds = findDistinctPromptIds(offset, limit);
        return promptIds.stream().map(this::getLatestView).toList();
    }

    public SystemPromptView create(SystemPromptRequest request, String userId) {
        var entity = new SystemPrompt();
        entity.id = UUID.randomUUID().toString();
        entity.promptId = UUID.randomUUID().toString();
        entity.name = request.name;
        entity.description = request.description;
        entity.content = request.content;
        entity.variables = extractVariables(request.content);
        entity.version = 1;
        entity.changelog = "initial version";
        entity.tags = request.tags;
        entity.userId = userId;
        entity.createdAt = ZonedDateTime.now();

        store.insert(entity);
        return toView(entity);
    }

    public SystemPromptView get(String promptId) {
        var entity = getLatestVersion(promptId);
        return toView(entity);
    }

    public SystemPromptView getByName(String name) {
        var entity = store.latestByName(name);
        if (entity == null) {
            throw new NotFoundException("system prompt not found, name=" + name);
        }
        return toView(entity);
    }

    public SystemPromptView update(String promptId, SystemPromptRequest request, String userId) {
        var current = getLatestVersion(promptId);

        var entity = new SystemPrompt();
        entity.id = UUID.randomUUID().toString();
        entity.promptId = promptId;
        entity.name = request.name != null ? request.name : current.name;
        entity.description = request.description != null ? request.description : current.description;
        entity.content = request.content != null ? request.content : current.content;
        entity.variables = extractVariables(entity.content);
        entity.version = current.version + 1;
        entity.changelog = request.changelog;
        entity.tags = request.tags != null ? request.tags : current.tags;
        entity.userId = userId;
        entity.createdAt = ZonedDateTime.now();

        store.insert(entity);
        return toView(entity);
    }

    public void delete(String promptId) {
        store.deleteByPromptId(promptId);
    }

    public List<SystemPromptVersionView> versions(String promptId) {
        return store.versions(promptId).stream().map(this::toVersionView).toList();
    }

    public SystemPromptView getVersion(String promptId, int version) {
        var entity = store.version(promptId, version);
        if (entity == null) {
            throw new RuntimeException("version not found, promptId=" + promptId + ", version=" + version);
        }
        return toView(entity);
    }

    public String resolveContent(String promptId) {
        return getLatestVersion(promptId).content;
    }

    SystemPrompt getLatestVersion(String promptId) {
        var entity = store.latestByPromptId(promptId);
        if (entity == null) {
            throw new RuntimeException("system prompt not found, promptId=" + promptId);
        }
        return entity;
    }

    private SystemPromptView getLatestView(String promptId) {
        return toView(getLatestVersion(promptId));
    }

    private List<String> findDistinctPromptIds(int offset, int limit) {
        // get all latest versions, sorted by createdAt desc, then paginate
        var all = store.listAll();

        var seen = new java.util.LinkedHashSet<String>();
        for (var entity : all) {
            seen.add(entity.promptId);
        }
        var ids = new ArrayList<>(seen);
        int end = Math.min(offset + limit, ids.size());
        if (offset >= ids.size()) return List.of();
        return ids.subList(offset, end);
    }

    List<String> extractVariables(String content) {
        if (content == null) return List.of();
        var variables = new ArrayList<String>();
        Matcher matcher = VARIABLE_PATTERN.matcher(content);
        while (matcher.find()) {
            String var = matcher.group(1);
            if (!variables.contains(var)) {
                variables.add(var);
            }
        }
        return variables;
    }

    private SystemPromptView toView(SystemPrompt entity) {
        var view = new SystemPromptView();
        view.id = entity.id;
        view.promptId = entity.promptId;
        view.name = entity.name;
        view.description = entity.description;
        view.content = entity.content;
        view.variables = entity.variables;
        view.version = entity.version;
        view.changelog = entity.changelog;
        view.tags = entity.tags;
        view.userId = entity.userId;
        view.createdAt = entity.createdAt;
        return view;
    }

    private SystemPromptVersionView toVersionView(SystemPrompt entity) {
        var view = new SystemPromptVersionView();
        view.version = entity.version;
        view.changelog = entity.changelog;
        view.content = entity.content;
        view.createdAt = entity.createdAt;
        return view;
    }

    public SystemPromptTestResponse test(String promptId, SystemPromptTestRequest request) {
        var entity = getLatestVersion(promptId);
        var resolvedPrompt = resolveVariables(entity.content, request.variables);

        var messages = new ArrayList<Message>();
        messages.add(Message.of(RoleType.SYSTEM, resolvedPrompt));
        messages.add(Message.of(RoleType.USER, request.userMessage));

        var model = request.model != null && !request.model.isBlank() ? request.model : null;
        var completionRequest = CompletionRequest.of(new CompletionRequest.CompletionRequestOptions(
            messages, null, null, model, null, Boolean.FALSE, null, null
        ));

        var provider = llmProviders.getProvider();
        var completionResponse = provider.completion(completionRequest);

        var response = new SystemPromptTestResponse();
        response.output = completionResponse.choices.getFirst().message.content;
        response.resolvedPrompt = resolvedPrompt;
        if (completionResponse.usage != null) {
            response.inputTokens = (long) completionResponse.usage.getPromptTokens();
            response.outputTokens = (long) completionResponse.usage.getCompletionTokens();
        }
        return response;
    }

    private String resolveVariables(String content, Map<String, String> variables) {
        if (content == null || variables == null || variables.isEmpty()) return content;
        String resolved = content;
        for (var entry : variables.entrySet()) {
            resolved = resolved.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return resolved;
    }
}
