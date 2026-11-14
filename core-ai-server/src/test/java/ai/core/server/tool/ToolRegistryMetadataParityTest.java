package ai.core.server.tool;

import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.tool.ToolRegistryMetadata;
import ai.core.server.tool.ToolRegistryService;
import ai.core.tool.BuiltinTools;
import ai.core.tool.ToolCall;
import ai.core.tool.registry.ToolProvider;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * B2 parity: the gate's builtin projection must equal the tool set the runtime provider resolves for the
 * same group ref. This is the regression that catches a new group or renamed tool drifting between the two.
 */
class ToolRegistryMetadataParityTest {
    @Test
    @SuppressWarnings("unchecked")
    void effectiveBuiltinNamesMatchRuntimeProvider() throws Exception {
        var service = new ToolRegistryService();
        var sets = new LinkedHashMap<String, List<ToolCall>>();
        var entries = new LinkedHashMap<String, ai.core.server.domain.ToolRegistryEntry>();
        for (var entry : BuiltinTools.GROUPED_SETS.entrySet()) {
            var registryEntry = new ai.core.server.domain.ToolRegistryEntry();
            registryEntry.id = "builtin:" + entry.getKey();
            registryEntry.name = entry.getKey();
            registryEntry.type = ai.core.server.domain.ToolType.BUILTIN;
            registryEntry.enabled = Boolean.TRUE;
            registryEntry.config = Map.of("set", entry.getKey());
            entries.put(registryEntry.id, registryEntry);
            sets.put(registryEntry.id, BuiltinTools.fromSet(entry.getKey(), null, null));
        }
        inject(service, "dynamicToolSets", sets);
        inject(service, "tools", entries);

        for (var entry : BuiltinTools.GROUPED_SETS.entrySet()) {
            var ref = ToolRef.of("builtin:" + entry.getKey(), ToolSourceType.BUILTIN);
            var projected = ToolRegistryMetadata.effectiveBuiltinToolNames(service, List.of(ref));
            var runtime = BuiltinTools.fromSet(entry.getKey(), null, null).stream().map(ToolCall::getName).toList();
            assertEquals(new java.util.LinkedHashSet<>(runtime), new java.util.LinkedHashSet<>(projected),
                "group " + entry.getKey());
        }
    }

    @Test
    void individualBuiltinGroupToolResolvesBySuffix() throws Exception {
        var service = new ToolRegistryService();
        var sets = new LinkedHashMap<String, List<ToolCall>>();
        sets.put("builtin:test-group", List.of(new NamedTool("list_agents"), new NamedTool("get_agent")));
        inject(service, "dynamicToolSets", sets);
        inject(service, "tools", new LinkedHashMap<String, ai.core.server.domain.ToolRegistryEntry>());

        var ref = ToolRef.of("builtin:test-group:list_agents", ToolSourceType.BUILTIN);
        assertEquals(List.of("list_agents"), ToolRegistryMetadata.effectiveBuiltinToolNames(service, List.of(ref)));
    }

    @SuppressWarnings("unchecked")
    private void inject(ToolRegistryService service, String fieldName, Object value) throws Exception {
        Field field = ToolRegistryService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        ((Map<String, Object>) field.get(service)).putAll((Map<String, Object>) value);
    }

    /** Minimal ToolCall used only to name a tool; execute is never called in metadata projection. */
    private static final class NamedTool extends ToolCall {
        private final String name;

        private NamedTool(String name) {
            this.name = name;
            new Builder().name(name).description("test tool").build(this);
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public ai.core.tool.ToolCallResult execute(String text) {
            return ai.core.tool.ToolCallResult.completed("");
        }

        private static final class Builder extends ToolCall.Builder<Builder, NamedTool> {
            @Override
            protected Builder self() {
                return this;
            }
        }
    }
}

