package ai.core.tool;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * @author stephen
 */
class ToolCallParameterTest {
    @Test
    void test() {
        assert ToolCallParameter.SUPPORT_TYPES.size() == ToolCallParameterType.values().length;
        assert ToolCallParameter.SUPPORT_TYPES.stream().map(Class::getSimpleName).collect(Collectors.toSet()).equals(Arrays.stream(ToolCallParameterType.values()).map(Enum::name).collect(Collectors.toSet()));
    }
}
