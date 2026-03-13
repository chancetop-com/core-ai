package ai.core.session.permission;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathExtractorTest {

    @Test
    void extractPathFromReadFile() {
        var path = PathExtractor.extractPath("read_file", Map.of("file_path", "/home/user/a.txt"));
        assertTrue(path.isPresent());
        assertEquals("/home/user/a.txt", path.get());
    }

    @Test
    void extractPathFromWriteFile() {
        var path = PathExtractor.extractPath("write_file", Map.of("file_path", "/tmp/out.txt"));
        assertTrue(path.isPresent());
        assertEquals("/tmp/out.txt", path.get());
    }

    @Test
    void extractPathFromEditFile() {
        var path = PathExtractor.extractPath("edit_file", Map.of("file_path", "/src/main.java"));
        assertTrue(path.isPresent());
        assertEquals("/src/main.java", path.get());
    }

    @Test
    void extractPathFromGlobFile() {
        var path = PathExtractor.extractPath("glob_file", Map.of("path", "/src"));
        assertTrue(path.isPresent());
        assertEquals("/src", path.get());
    }

    @Test
    void unknownToolReturnsEmpty() {
        var path = PathExtractor.extractPath("shell_command", Map.of("command", "ls -la"));
        assertFalse(path.isPresent());
    }

    @Test
    void nullArgumentsReturnsEmpty() {
        var path = PathExtractor.extractPath("read_file", null);
        assertFalse(path.isPresent());
    }

    @Test
    void missingPathParamReturnsEmpty() {
        var path = PathExtractor.extractPath("read_file", Map.of("other_param", "value"));
        assertFalse(path.isPresent());
    }
}
