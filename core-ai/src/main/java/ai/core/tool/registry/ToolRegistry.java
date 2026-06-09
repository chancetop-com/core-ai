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
import java.util.concurrent.atomic.AtomicLong;

/**
 * Central tool registry — the single source of truth for which tools exist
 * and how they are dispatched.
 *
 * <h3>Responsibilities</h3>
 * <ul>
 *   <li><b>Register</b> — accept tools from {@link ToolProvider}s, resolve
 *       name conflicts via priority-based overlay</li>
 *   <li><b>Materialize</b> — produce a frozen {@link ToolMaterialization}
 *       (model-visible definitions + dispatch map) from the current provider
 *       set. Tools with {@link ToolExposure#DIRECT} go into definitions;
 *       tools with any exposure go into the dispatch map.</li>
 *   <li><b>Dispatch</b> — execute a function call through a materialized
 *       tool set, with epoch-based staleness detection</li>
 * </ul>
 *
 * <h3>Constraint model</h3>
 * <p>
 * Per-environment constraints (OS, model provider, feature flags) are expressed
 * by registering different {@link ToolProvider} sets at build time — not by
 * runtime filtering. For example, a Windows deployment registers a
 * {@code WindowsToolProvider} that only provides PowerShell; a macOS deployment
 * registers a {@code MacToolProvider} that provides Bash and AppleScript.
 * Shared tools go into a {@code CommonToolProvider} registered on all platforms.
 *
 * <h3>Thread safety</h3>
 * Provider registration is safe from any thread.
 * {@link #materialize(ContextSnapshot)} reads a consistent snapshot of the
 * current provider map.
 *
 * @author Lim Chen
 */
public class ToolRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, ToolProvider> providers = new ConcurrentHashMap<>();
    private final AtomicLong epoch = new AtomicLong();

    public ToolRegistry() {
    }

    public void registerProvider(ToolProvider provider) {
        var previous = providers.put(provider.id(), provider);
        epoch.incrementAndGet();
        if (previous != null) {
            LOGGER.info("replaced provider, id={}", provider.id());
        } else {
            LOGGER.info("registered provider, id={}", provider.id());
        }
    }

    public void unregisterProvider(String providerId) {
        providers.remove(providerId);
        epoch.incrementAndGet();
        LOGGER.info("unregistered provider, id={}", providerId);
    }

    long currentEpoch() {
        return epoch.get();
    }

    public ToolMaterialization materialize() {
        var allTools = collectTools();
        var epochSnapshot = currentEpoch();
        var definitions = new ArrayList<Tool>();
        var dispatchMap = new LinkedHashMap<String, ToolCall>();

        for (var entry : allTools.entrySet()) {
            var tool = entry.getValue();
            if (tool.getExposure() == ToolExposure.DIRECT) {
                definitions.add(tool.toTool());
            }
            dispatchMap.put(entry.getKey(), tool);
        }
        LOGGER.debug("materialized {} definitions from {} tools (epoch={})", definitions.size(), allTools.size(), epochSnapshot);
        return new ToolMaterialization(epochSnapshot, definitions, dispatchMap);
    }

    public ToolCallResult dispatch(ToolMaterialization materialization, FunctionCall call, ExecutionContext context) {
        if (materialization.epoch() != currentEpoch()) {
            LOGGER.warn("stale tool call rejected, name={}, id={}", call.function.name, call.id);
            return ToolCallResult.failed("Stale tool call: " + call.function.name);
        }
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
