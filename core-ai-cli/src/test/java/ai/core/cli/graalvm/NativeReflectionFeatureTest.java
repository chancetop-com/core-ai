package ai.core.cli.graalvm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class NativeReflectionFeatureTest {
    @Test
    void configuredMcpServiceLoaderClassesExist() {
        var classNames = NativeReflectionFeature.configuredReflectionClassNames().stream()
            .filter(className -> className.startsWith("io.modelcontextprotocol.json."))
            .toList();

        assertFalse(classNames.isEmpty());
        for (String className : classNames) {
            assertNotNull(loadClass(className), className);
        }
    }

    private Class<?> loadClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException error) {
            throw new AssertionError("configured reflection class does not exist: " + className, error);
        }
    }
}
