package ai.core.server.tool;

import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolRegistry;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.domain.ToolType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolRefResolverTest {
    @Test
    void registryTypeOverridesIncorrectRequestType() {
        var registry = new ToolRegistry();
        registry.id = "custom-builtin";
        registry.type = ToolType.BUILTIN;
        registry.config = Map.of("set", "builtin-planning");

        var ref = new ToolRef();
        ref.id = "custom-builtin";
        ref.type = ToolSourceType.MCP;

        var resolver = new ToolRefResolver(Map.of(registry.id, registry), null, Map.of());

        var resolved = resolver.resolve(java.util.List.of(ref));

        assertEquals(ToolRegistryService.BUILTIN_TOOL_SETS.get("builtin-planning").size(), resolved.size());
    }
}
