package ai.core.tool.registry;

import ai.core.agent.ExecutionContext;
import ai.core.llm.domain.FunctionCall;
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
        void shouldProvideAllBuiltinTools() {
            var provider = new BuiltinToolProvider();
            var tools = provider.provide();

            assertEquals("builtin", provider.id());
            assertEquals(10, provider.priority());
            assertTrue(tools.size() > 0, "should provide builtin tools");
            assertTrue(tools.containsKey("read_file"));
            assertTrue(tools.containsKey("write_file"));
            assertTrue(tools.containsKey("shell_command"));
        }

        @Test
        void shouldRegisterAndMaterializeBuiltinTools() {
            registry.registerProvider(new BuiltinToolProvider());

            var mat = registry.materialize(ContextSnapshot.builder().build());
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

            var mat = registry.materialize(ContextSnapshot.builder().build());
            assertEquals(1, mat.definitions().size());
            assertEquals("high prio", mat.definitions().getFirst().function.description);
        }

        @Test
        void shouldMergeToolsFromDifferentProviders() {
            registry.registerProvider(createProvider("p1", 10,
                    Map.of("read_file", newEchoTool("read_file", "reads", ToolExposure.DIRECT))));
            registry.registerProvider(createProvider("p2", 20,
                    Map.of("write_file", newEchoTool("write_file", "writes", ToolExposure.DIRECT))));

            var mat = registry.materialize(ContextSnapshot.builder().build());
            var names = mat.definitions().stream().map(t -> t.function.name).sorted().toList();
            assertEquals(List.of("read_file", "write_file"), names);
        }

        @Test
        void shouldRemoveToolsOnProviderUnregistration() {
            registry.registerProvider(createProvider("temp", 10,
                    Map.of("echo", newEchoTool("echo", "echo", ToolExposure.DIRECT))));
            assertEquals(1, registry.materialize(ContextSnapshot.builder().build()).definitions().size());

            registry.unregisterProvider("temp");
            assertEquals(0, registry.materialize(ContextSnapshot.builder().build()).definitions().size());
        }

        @Test
        void shouldIncrementEpochOnProviderChange() {
            registry.registerProvider(createProvider("p1", 10, Map.of()));
            var epoch1 = registry.materialize(ContextSnapshot.builder().build()).epoch();

            registry.registerProvider(createProvider("p2", 20, Map.of()));
            var epoch2 = registry.materialize(ContextSnapshot.builder().build()).epoch();

            assertTrue(epoch2 > epoch1);
        }
    }

    @Nested
    class Materialize {
        @Test
        void shouldReturnEmptyWhenNoProviders() {
            var mat = registry.materialize(ContextSnapshot.builder().build());
            assertTrue(mat.definitions().isEmpty());
        }

        @Test
        void shouldIncludeDirectToolsInDefinitions() {
            registry.registerProvider(createProvider("test", 10, Map.of(
                    "alpha", newEchoTool("alpha", "alpha", ToolExposure.DIRECT),
                    "beta", newEchoTool("beta", "beta", ToolExposure.DIRECT)
            )));

            var mat = registry.materialize(ContextSnapshot.builder().build());
            var names = mat.definitions().stream().map(t -> t.function.name).sorted().toList();

            assertEquals(List.of("alpha", "beta"), names);
        }

        @Test
        void shouldOmitHiddenToolsFromDefinitions() {
            registry.registerProvider(createProvider("test", 10, Map.of(
                    "visible", newEchoTool("visible", "visible", ToolExposure.DIRECT),
                    "hidden", newEchoTool("hidden", "hidden", ToolExposure.HIDDEN)
            )));

            var mat = registry.materialize(ContextSnapshot.builder().build());
            assertEquals(List.of("visible"), mat.definitions().stream().map(t -> t.function.name).toList());

            // hidden tool is still dispatchable
            var call = FunctionCall.of("call", "function", "hidden", "{\"msg\":\"test\"}");
            var result = registry.dispatch(mat, call, ctx);
            assertTrue(result.isCompleted());
        }

        @Test
        void shouldOmitDeferredToolsFromDefinitions() {
            registry.registerProvider(createProvider("test", 10, Map.of(
                    "direct", newEchoTool("direct", "direct", ToolExposure.DIRECT),
                    "deferred", newEchoTool("deferred", "deferred", ToolExposure.DEFERRED)
            )));

            var mat = registry.materialize(ContextSnapshot.builder().build());
            assertEquals(List.of("direct"), mat.definitions().stream().map(t -> t.function.name).toList());

            // deferred is still dispatchable (LLM can call after tool_search)
            var call = FunctionCall.of("call", "function", "deferred", "{\"msg\":\"test\"}");
            var result = registry.dispatch(mat, call, ctx);
            assertTrue(result.isCompleted());
        }

        @Test
        void shouldDefaultToDirectWhenExposureNotSet() {
            var tool = newEchoTool("default", "no exposure set", null);
            assertEquals(ToolExposure.DIRECT, tool.getExposure());
        }
    }

    @Nested
    class Dispatch {
        @Test
        void shouldExecuteToolSuccessfully() {
            registry.registerProvider(createProvider("test", 10,
                    Map.of("echo", newEchoTool("echo", "echo", ToolExposure.DIRECT))));

            var mat = registry.materialize(ContextSnapshot.builder().build());
            var call = FunctionCall.of("call_1", "function", "echo", "{\"msg\":\"hello\"}");

            var result = registry.dispatch(mat, call, ctx);
            assertTrue(result.isCompleted());
            assertTrue(result.getResult().contains("hello"));
        }

        @Test
        void shouldReturnErrorForUnknownTool() {
            registry.registerProvider(createProvider("test", 10,
                    Map.of("echo", newEchoTool("echo", "echo", ToolExposure.DIRECT))));

            var mat = registry.materialize(ContextSnapshot.builder().build());
            var call = FunctionCall.of("call_1", "function", "nonexistent", "{}");

            var result = registry.dispatch(mat, call, ctx);
            assertTrue(result.isFailed());
            assertTrue(result.getResult().contains("Unknown tool"));
        }

        @Test
        void shouldRejectStaleCallAfterProviderChanged() {
            registry.registerProvider(createProvider("v1", 10,
                    Map.of("echo", newEchoTool("echo", "v1", ToolExposure.DIRECT))));
            var mat = registry.materialize(ContextSnapshot.builder().build());

            registry.registerProvider(createProvider("v2", 5,
                    Map.of("echo", newEchoTool("echo", "v2", ToolExposure.DIRECT))));

            var call = FunctionCall.of("call_1", "function", "echo", "{\"msg\":\"hello\"}");
            var result = registry.dispatch(mat, call, ctx);

            assertTrue(result.isFailed());
            assertTrue(result.getResult().contains("Stale tool call"));
        }

        @Test
        void shouldRejectStaleCallAfterProviderRemoved() {
            registry.registerProvider(createProvider("v1", 10,
                    Map.of("echo", newEchoTool("echo", "echo", ToolExposure.DIRECT))));
            var mat = registry.materialize(ContextSnapshot.builder().build());

            registry.unregisterProvider("v1");

            var call = FunctionCall.of("call_1", "function", "echo", "{\"msg\":\"hello\"}");
            var result = registry.dispatch(mat, call, ctx);

            assertTrue(result.isFailed());
            assertTrue(result.getResult().contains("Stale tool call"));
        }
    }

    @Nested
    class ContextSnapshotBuilder {
        @Test
        void shouldSetDefaults() {
            var snapshot = ContextSnapshot.builder().build();

            assertEquals(ContextSnapshot.OperatingSystem.UNKNOWN, snapshot.os());
            assertTrue(snapshot.permissions().isEmpty());
            assertTrue(snapshot.featureFlags().isEmpty());
            assertTrue(snapshot.isOnline());
        }

        @Test
        void shouldBuildWithModelAndOs() {
            var snapshot = ContextSnapshot.builder()
                    .modelProvider("anthropic")
                    .modelName("claude-sonnet-4-6")
                    .os(ContextSnapshot.OperatingSystem.MACOS)
                    .build();

            assertEquals("anthropic", snapshot.modelProvider());
            assertEquals("claude-sonnet-4-6", snapshot.modelName());
            assertEquals(ContextSnapshot.OperatingSystem.MACOS, snapshot.os());
        }

        @Test
        void shouldBuildWithPermissionsAndFlags() {
            var snapshot = ContextSnapshot.builder()
                    .addPermission("admin")
                    .addFeatureFlag("standalone_web_search")
                    .isOnline(false)
                    .build();

            assertTrue(snapshot.permissions().contains("admin"));
            assertTrue(snapshot.featureFlags().contains("standalone_web_search"));
            assertTrue(!snapshot.isOnline());
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
