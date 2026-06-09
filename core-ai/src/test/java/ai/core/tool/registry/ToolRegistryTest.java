package ai.core.tool.registry;

import ai.core.agent.ExecutionContext;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameterType;
import ai.core.tool.ToolCallResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Lim Chen
 */
class ToolRegistryTest {
    private ToolRegistry registry;
    private ExecutionContext ctx;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
        ctx = ExecutionContext.builder()
                .customVariable("workspace", "/tmp/test")
                .build();
    }

    @Nested
    class BuiltinProvider {
        @Test
        void shouldProvideBuiltinTools() {
            var provider = new BuiltinToolProvider();
            assertEquals(ToolProvider.BUILTIN, provider.id());
            assertEquals(10, provider.priority());
        }

        @Test
        void shouldRegisterAndMaterializeBuiltinTools() {
            registry.registerProvider(new BuiltinToolProvider());

            var mat = registry.materialize();
            var names = mat.definitions().stream().map(t -> t.function.name).toList();

            assertTrue(names.contains("read_file"));
            assertTrue(names.contains("write_file"));
        }
    }

    @Nested
    class Registration {
        @Test
        void shouldResolveNameConflictByPriority() {
            registry.registerProvider(createProvider("high", 5,
                    Map.of("shared", newEchoTool("shared", "high prio", ToolExposure.DIRECT))));
            registry.registerProvider(createProvider("low", 100,
                    Map.of("shared", newEchoTool("shared", "low prio", ToolExposure.DIRECT))));

            var mat = registry.materialize();
            assertEquals(1, mat.definitions().size());
            assertEquals("high prio", mat.definitions().getFirst().function.description);
        }

        @Test
        void shouldMergeToolsFromDifferentProviders() {
            registry.registerProvider(createProvider("p1", 10,
                    Map.of("read_file", newEchoTool("read_file", "reads", ToolExposure.DIRECT))));
            registry.registerProvider(createProvider("p2", 20,
                    Map.of("write_file", newEchoTool("write_file", "writes", ToolExposure.DIRECT))));

            var mat = registry.materialize();
            var names = mat.definitions().stream().map(t -> t.function.name).sorted().toList();
            assertEquals(List.of("read_file", "write_file"), names);
        }

        @Test
        void shouldRemoveToolsOnProviderUnregistration() {
            registry.registerProvider(createProvider("temp", 10,
                    Map.of("echo", newEchoTool("echo", "echo", ToolExposure.DIRECT))));
            assertEquals(1, registry.materialize().definitions().size());

            registry.unregisterProvider("temp");
            assertEquals(0, registry.materialize().definitions().size());
        }
    }

    @Nested
    class Materialize {
        @Test
        void shouldReturnEmptyWhenNoProviders() {
            var mat = registry.materialize();
            assertTrue(mat.definitions().isEmpty());
        }

        @Test
        void shouldIncludeDirectToolsInDefinitions() {
            registry.registerProvider(createProvider("test", 10, Map.of(
                    "alpha", newEchoTool("alpha", "alpha", ToolExposure.DIRECT),
                    "beta", newEchoTool("beta", "beta", ToolExposure.DIRECT)
            )));

            var mat = registry.materialize();
            var names = mat.definitions().stream().map(t -> t.function.name).sorted().toList();

            assertEquals(List.of("alpha", "beta"), names);
        }

        @Test
        void shouldOmitHiddenToolsFromDefinitions() {
            registry.registerProvider(createProvider("test", 10, Map.of(
                    "visible", newEchoTool("visible", "visible", ToolExposure.DIRECT),
                    "hidden", newEchoTool("hidden", "hidden", ToolExposure.HIDDEN)
            )));

            var mat = registry.materialize();
            assertEquals(List.of("visible"), mat.definitions().stream().map(t -> t.function.name).toList());

            var hidden = mat.getDispatchMap().get("hidden");
            assertNotNull(hidden);
            var result = hidden.execute("{\"msg\":\"test\"}", ctx);
            assertTrue(result.isCompleted());
        }

        @Test
        void shouldOmitDeferredToolsFromDefinitions() {
            registry.registerProvider(createProvider("test", 10, Map.of(
                    "direct", newEchoTool("direct", "direct", ToolExposure.DIRECT),
                    "deferred", newEchoTool("deferred", "deferred", ToolExposure.DEFERRED)
            )));

            var mat = registry.materialize();
            assertEquals(List.of("direct"), mat.definitions().stream().map(t -> t.function.name).toList());

            var deferred = mat.getDispatchMap().get("deferred");
            assertNotNull(deferred);
            var result = deferred.execute("{\"msg\":\"test\"}", ctx);
            assertTrue(result.isCompleted());
        }

        @Test
        void shouldDefaultToDirectWhenExposureNotSet() {
            var tool = newEchoTool("default", "no exposure set", null);
            assertEquals(ToolExposure.DIRECT, tool.getExposure());
        }
    }

    @Nested
    class DispatchMap {
        @Test
        void shouldFindAndExecuteToolByName() {
            registry.registerProvider(createProvider("test", 10,
                    Map.of("echo", newEchoTool("echo", "echo", ToolExposure.DIRECT))));

            var mat = registry.materialize();
            var tool = mat.getDispatchMap().get("echo");
            assertNotNull(tool);
            var result = tool.execute("{\"msg\":\"hello\"}", ctx);
            assertTrue(result.isCompleted());
            assertTrue(result.getResult().contains("hello"));
        }

        @Test
        void shouldReturnNullForUnknownTool() {
            registry.registerProvider(createProvider("test", 10,
                    Map.of("echo", newEchoTool("echo", "echo", ToolExposure.DIRECT))));

            var mat = registry.materialize();
            assertNull(mat.getDispatchMap().get("nonexistent"));
        }
    }

    @Nested
    class Factory {
        @Test
        void shouldCreateRegistryWithBuiltinTools() {
            var factoryRegistry = ToolRegistryFactory.create(new FactoryContext(null, null, false));

            var mat = factoryRegistry.materialize();
            var names = mat.definitions().stream().map(t -> t.function.name).toList();

            assertTrue(names.contains("read_file"));
            assertTrue(names.contains("write_file"));
        }
    }

    // -- helpers --

    static ToolProvider createProvider(String id, int priority, Map<String, ToolCall> tools) {
        return new ToolProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public int priority() {
                return priority;
            }

            @Override
            public Map<String, ToolCall> provide() {
                return tools;
            }
        };
    }

    static ToolCall newEchoTool(String name, String description, ToolExposure exposure) {
        var tool = new EchoTool(name, description);
        if (exposure != null) {
            tool.setExposure(exposure);
        }
        return tool;
    }

    static class EchoTool extends ToolCall {
        EchoTool(String name, String description) {
            setName(name);
            setDescription(description);
            setParameters(List.of(
                    ToolCallParameter.builder()
                            .name("msg")
                            .description("Message to echo")
                            .type(ToolCallParameterType.STRING)
                            .required(true)
                            .build()
            ));
        }

        @Override
        public ToolCallResult execute(String arguments) {
            return ToolCallResult.completed("echo: " + arguments);
        }
    }
}
