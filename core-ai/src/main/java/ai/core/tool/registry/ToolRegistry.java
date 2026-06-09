package ai.core.tool.registry;

import ai.core.llm.domain.Tool;
import ai.core.tool.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Central tool registry — the single source of truth for which tools exist
 * and how they are dispatched.
 *
 * @author Lim Chen
 */
public class ToolRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, ToolProvider> providers = new ConcurrentHashMap<>();

    public ToolRegistry() {
    }

    public void registerProvider(ToolProvider provider) {
        var previous = providers.put(provider.id(), provider);
        if (previous != null) {
            LOGGER.info("replaced provider, id={}", provider.id());
        } else {
            LOGGER.info("registered provider, id={}", provider.id());
        }
    }

    public void registerTools(List<ToolCall> toolCalls) {
        registerTools(toolCalls, "user-provided");
    }

    public void registerTools(List<ToolCall> toolCalls, String providerId) {
        registerProvider(new ToolProvider() {
            @Override
            public String id() {
                return providerId;
            }

            @Override
            public int priority() {
                return 5;
            }

            @Override
            public Map<String, ToolCall> provide() {
                return toolCalls.stream().collect(Collectors.toMap(
                        ToolCall::getName,
                        Function.identity(),
                        (existing, _) -> existing
                ));
            }
        });
    }

    public List<ToolCall> getToolCalls() {
        return List.copyOf(materialize().getDispatchMap().values());
    }

    public void unregisterProvider(String providerId) {
        providers.remove(providerId);
        LOGGER.info("unregistered provider, id={}", providerId);
    }

    public ToolMaterialization materialize() {
        var allTools = collectTools();
        var definitions = new ArrayList<Tool>();
        var dispatchMap = new LinkedHashMap<String, ToolCall>();

        for (var entry : allTools.entrySet()) {
            var tool = entry.getValue();
            if (tool.getExposure() == ToolExposure.DIRECT) {
                definitions.add(tool.toTool());
            }
            dispatchMap.put(entry.getKey(), tool);
        }
        LOGGER.debug("materialized {} definitions from {} tools", definitions.size(), allTools.size());
        return new ToolMaterialization(definitions, dispatchMap);
    }

    private Map<String, ToolCall> collectTools() {
        var sorted = providers.values().stream()
                .sorted(Comparator.comparingInt(ToolProvider::priority))
                .toList();
        var result = new LinkedHashMap<String, ToolCall>();
        for (var provider : sorted) {
            try {
                for (var entry : provider.provide().entrySet()) {
                    result.putIfAbsent(entry.getKey(), entry.getValue());
                }
            } catch (Exception e) {
                LOGGER.warn("provider {} failed, skipping, id={}", provider.getClass().getSimpleName(), provider.id(), e);
            }
        }
        return result;
    }
}
