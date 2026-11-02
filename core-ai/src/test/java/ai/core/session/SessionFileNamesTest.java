package ai.core.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @author stephen
 */
class SessionFileNamesTest {
    @Test
    void rejectsTraversalAndControlCharacters() {
        assertThrows(IllegalArgumentException.class, () -> SessionFileNames.check("../x"));
        assertThrows(IllegalArgumentException.class, () -> SessionFileNames.check("a/b"));
        assertThrows(IllegalArgumentException.class, () -> SessionFileNames.check("a\\b"));
        assertThrows(IllegalArgumentException.class, () -> SessionFileNames.check(".."));
        assertThrows(IllegalArgumentException.class, () -> SessionFileNames.check(".hidden"));
        assertThrows(IllegalArgumentException.class, () -> SessionFileNames.check("a\nb"));
        assertThrows(IllegalArgumentException.class, () -> SessionFileNames.check(""));
    }

    @Test
    void acceptsSubagentKeysAndOtherSafeCharacters() {
        assertDoesNotThrow(() -> SessionFileNames.check("s1~sub~sa-1"));
        assertDoesNotThrow(() -> SessionFileNames.check("s1~sub~_index"));
        assertDoesNotThrow(() -> SessionFileNames.check("task#1"));
        assertDoesNotThrow(() -> SessionFileNames.check("任务-1"));
    }
}
