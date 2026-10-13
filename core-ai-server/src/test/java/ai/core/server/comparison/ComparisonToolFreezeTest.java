package ai.core.server.comparison;

import ai.core.api.server.tool.ToolRefView;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolSourceType;
import core.framework.web.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class ComparisonToolFreezeTest {
    @Test
    void defaultEffectiveToolsAreBuiltinOnlyNormalizedAndDeduplicated() {
        var legacy = new ToolRef();
        legacy.id = "builtin:tool-a";
        var configured = List.of(
            legacy,
            ToolRef.of("mcp-tool:srv:tool-b", ToolSourceType.MCP),
            ToolRef.of("builtin:tool-a", ToolSourceType.BUILTIN));

        var result = ComparisonToolFreeze.defaultEffective(configured);

        assertEquals(1, result.size());
        assertEquals("builtin:tool-a", result.getFirst().id);
        assertEquals(ToolSourceType.BUILTIN, result.getFirst().type);
    }

    @Test
    void requestedToolsOutsideTheVariantConfigAreRejected() {
        var requested = List.of(view("mcp-tool:srv:tool-b", "MCP"));
        var configured = List.of(ToolRef.of("builtin:tool-a", ToolSourceType.BUILTIN));

        assertThrows(BadRequestException.class,
            () -> ComparisonToolFreeze.resolve(requested, null, configured));
    }

    @Test
    void explicitConfirmationWinsOverStoredListWhichWinsOverTheDefault() {
        var configured = List.of(
            ToolRef.of("builtin:tool-a", ToolSourceType.BUILTIN),
            ToolRef.of("builtin:tool-c", ToolSourceType.BUILTIN));
        var requested = List.of(view("builtin:tool-a", "BUILTIN"));
        var stored = List.of(ToolRef.of("builtin:tool-c", ToolSourceType.BUILTIN));

        assertEquals(List.of("builtin:tool-a"),
            ComparisonToolFreeze.resolve(requested, stored, configured).stream().map(ref -> ref.id).toList());
        assertEquals(List.of("builtin:tool-c"),
            ComparisonToolFreeze.resolve(null, stored, configured).stream().map(ref -> ref.id).toList());
        assertEquals(List.of("builtin:tool-a", "builtin:tool-c"),
            ComparisonToolFreeze.resolve(null, null, configured).stream().map(ref -> ref.id).toList());
    }

    @Test
    void explicitEmptyConfirmationKeepsNoTools() {
        var configured = List.of(ToolRef.of("builtin:tool-a", ToolSourceType.BUILTIN));

        var result = ComparisonToolFreeze.resolve(List.of(), null, configured);

        assertTrue(result.isEmpty());
    }

    private ToolRefView view(String id, String type) {
        var view = new ToolRefView();
        view.id = id;
        view.type = type;
        return view;
    }
}
