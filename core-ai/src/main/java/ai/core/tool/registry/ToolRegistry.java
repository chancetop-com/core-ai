package ai.core.tool.registry;

import ai.core.agent.ExecutionContext;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Tool;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    public ToolCallResult dispatch(ToolMaterialization materialization, FunctionCall call, ExecutionContext context) {
        var tool = materialization.getDispatchMap().get(call.function.name);
        if (tool == null) {
            return ToolCallResult.failed("Unknown tool: " + call.function.name);
        }
        return tool.execute(call.function.arguments, context);
    }

    private Map<String, ToolCall> collectTools() {
        var sorted = providers.values().stream()
            .sorted(Comparator.comparingInt(ToolProvider::priority).reversed())
            .toList();
        var result = new LinkedHashMap<String, ToolCall>();
        for (var provider : sorted) {
            try {
                result.putAll(provider.provide());
            } catch (Exception e) {
                LOGGER.warn("provider {} failed, skipping, id={}", provider.getClass().getSimpleName(), provider.id(), e);
            }
        }
        return result;
    }
}
