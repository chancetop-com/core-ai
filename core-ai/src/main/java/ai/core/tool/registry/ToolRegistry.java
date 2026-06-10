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
        if (provider == null) {
            LOGGER.debug("null provider");
            return;
        }
        var previous = providers.put(provider.id(), provider);
        if (previous != null) {
            LOGGER.info("replaced provider, id={}", provider.id());
        } else {
            LOGGER.info("registered provider, id={}", provider.id());
        }
    }

    public List<ToolCall> getToolCalls() {
        return List.copyOf(materialize().getDispatchMap().values());
    }

    public void unregisterProvider(String providerId) {
        providers.remove(providerId);
        LOGGER.info("unregistered provider, id={}", providerId);
    }

    public ToolMaterialization materialize() {
        var collected = collectTools();
        var definitions = new ArrayList<Tool>();
        var dispatchMap = new LinkedHashMap<String, ToolCall>();

        for (var entry : collected.tools.entrySet()) {
            var tool = entry.getValue();
            if (tool.getExposure() == ToolExposure.DIRECT) {
                definitions.add(tool.toTool());
            }
            dispatchMap.put(entry.getKey(), tool);
        }
        LOGGER.debug("materialized {} definitions from {} tools", definitions.size(), collected.tools.size());
        return new ToolMaterialization(definitions, dispatchMap, collected.toolProviderIndex);
    }

    private CollectResult collectTools() {
        var sorted = providers.values().stream()
                .sorted(Comparator.comparingInt(ToolProvider::priority))
                .toList();
        var tools = new LinkedHashMap<String, ToolCall>();
        var toolProviderIndex = new LinkedHashMap<String, String>();
        for (var provider : sorted) {
            try {
                var toolMap = provider.provide();
                for (var entry : toolMap.entrySet()) {
                    var name = entry.getKey();
                    if (tools.putIfAbsent(name, entry.getValue()) == null) {
                        toolProviderIndex.put(name, provider.id());
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("provider {} failed, skipping, id={}", provider.getClass().getSimpleName(), provider.id(), e);
            }
        }
        return new CollectResult(tools, toolProviderIndex);
    }

    private record CollectResult(Map<String, ToolCall> tools, Map<String, String> toolProviderIndex) {}

    public ToolProvider getProvider(String providerId) {
        return providers.get(providerId);
    }
}
