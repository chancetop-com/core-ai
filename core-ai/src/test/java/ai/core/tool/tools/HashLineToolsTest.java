package ai.core.tool.tools;

import core.framework.json.JSON;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author lim chen
 */
class HashLineToolsTest {
    private HashReadFileTool readTool;
    private HashEditFileTool editTool;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        readTool = HashReadFileTool.builder().build();
        editTool = HashEditFileTool.builder().build();
    }

    // ── HashLine: hash computation ────────────────────────────────────────────

    @Test
    void hashIsDeterministic() {
        assertEquals(HashLine.computeHash("    void foo() {", 5), HashLine.computeHash("    void foo() {", 5));
    }

    @Test
    void hashDiffersForDifferentContent() {
        assertNotEquals(HashLine.computeHash("    void foo() {", 5), HashLine.computeHash("    void bar() {", 5));
    }

    @Test
    void blankLinesUseSeedFromLineNumber() {
        // Blank lines use lineNumber as seed — not all pairs differ in 256-value space,
        // but across a range of positions collisions should be rare.
        long distinct = java.util.stream.IntStream.rangeClosed(1, 20)
                .mapToObj(i -> HashLine.computeHash("", i))
                .distinct().count();
        assertTrue(distinct > 10, "Expected mostly distinct hashes for blank lines 1-20, got " + distinct);
    }

    @Test
    void punctuationOnlyLinesUseSeedFromLineNumber() {
        // Same: not guaranteed unique per pair, but spread over a range should differ
        long distinct = java.util.stream.IntStream.rangeClosed(1, 20)
                .mapToObj(i -> HashLine.computeHash("{", i))
                .distinct().count();
        assertTrue(distinct > 10, "Expected mostly distinct hashes for '{' lines 1-20, got " + distinct);
    }

    @Test
    void formatLineUsesColonSeparator() {
        String formatted = HashLine.formatLine(5, "VK", "void foo() {");
        assertEquals("5#VK:void foo() {", formatted);
    }

    @Test
    void parseRefBasic() {
        var ref = HashLine.parseRef("5#VK");
        assertEquals(5, ref.lineNumber());
        assertEquals("VK", ref.hash());
    }

    @Test
    void parseRefAllowsDiffPrefixes() {
        // parseTag allows leading ">+-" chars (LLM output from diff context)
        var ref = HashLine.parseRef(">>> 5#VK");
        assertEquals(5, ref.lineNumber());
        assertEquals("VK", ref.hash());
    }

    @Test
    void parseRefInvalidThrows() {
        assertThrows(IllegalArgumentException.class, () -> HashLine.parseRef("5"));
        assertThrows(IllegalArgumentException.class, () -> HashLine.parseRef("abc#VK"));
    }

    // ── HashLine: content prefix stripping ───────────────────────────────────

    @Test
    void parseContentStripsHashPrefixWhenAllLinesHaveIt() {
        // LLM sometimes copies hashline output verbatim into content
        var lines = HashLine.parseContent(List.of("1#VK:line one", "2#XJ:line two"));
        assertArrayEquals(new String[]{"line one", "line two"}, lines);
    }

    @Test
    void parseContentNoStripWhenMixed() {
        var lines = HashLine.parseContent(List.of("1#VK:line one", "plain line"));
        assertArrayEquals(new String[]{"1#VK:line one", "plain line"}, lines);
    }

    @Test
    void parseContentHandlesStringInput() {
        var lines = HashLine.parseContent("line one\nline two");
        assertArrayEquals(new String[]{"line one", "line two"}, lines);
    }

    @Test
    void parseContentHandlesNull() {
        assertArrayEquals(new String[0], HashLine.parseContent(null));
    }

    // ── HashReadFileTool ──────────────────────────────────────────────────────

    @Test
    void readOutputUsesColonFormat() throws IOException {
        Path file = tempDir.resolve("A.java");
        Files.writeString(file, "public class A {\n    void foo() {\n    }\n}");

        var result = readTool.execute(JSON.toJSON(Map.of("file_path", file.toString())));

        // Each line must match LINENUM#XX:content
        for (String line : result.getResult().split("\n")) {
            assertTrue(line.matches("\\d+#[ZPMQVRWSNKTXJBYH]{2}:.*"), "bad format: " + line);
        }
    }

    @Test
    void readFileNotFound() {
        var result = readTool.execute(JSON.toJSON(Map.of("file_path", "/nonexistent/file.java")));
        assertTrue(result.getResult().startsWith("Error:"));
    }

    // ── HashEditFileTool: range replace ──────────────────────────────────────

    @Test
    void replaceSingleLine() throws IOException {
        Path file = tempDir.resolve("A.java");
        Files.writeString(file, "line1\nline2\nline3\n");

        var pos = readRef(file, 2);
        var editResult = editTool.execute(JSON.toJSON(Map.of("edits", List.of(
                Map.of("path", file.toString(),
                        "loc", Map.of("range", Map.of("pos", pos, "end", pos)),
                        "content", List.of("replaced"))
        ))));

        assertNoError(editResult.getResult());
        assertEquals("line1\nreplaced\nline3\n", Files.readString(file));
    }

    @Test
    void replaceLineRange() throws IOException {
        Path file = tempDir.resolve("A.java");
        Files.writeString(file, "a\nb\nc\nd\n");

        var pos = readRef(file, 2);
        var end = readRef(file, 3);
        var editResult = editTool.execute(JSON.toJSON(Map.of("edits", List.of(
                Map.of("path", file.toString(),
                        "loc", Map.of("range", Map.of("pos", pos, "end", end)),
                        "content", List.of("x", "y"))
        ))));

        assertNoError(editResult.getResult());
        assertEquals("a\nx\ny\nd\n", Files.readString(file));
    }

    @Test
    void deleteRangeWithEmptyContent() throws IOException {
        Path file = tempDir.resolve("A.java");
        Files.writeString(file, "a\nb\nc\n");

        var pos = readRef(file, 2);
        // empty content list = delete the range
        var editResult = editTool.execute(JSON.toJSON(Map.of("edits", List.of(
                Map.of("path", file.toString(),
                        "loc", Map.of("range", Map.of("pos", pos, "end", pos)),
                        "content", List.of())
        ))));

        assertNoError(editResult.getResult());
        assertEquals("a\nc\n", Files.readString(file));
    }

    // ── HashEditFileTool: file-level loc ─────────────────────────────────────

    @Test
    void appendToFile() throws IOException {
        Path file = tempDir.resolve("A.java");
        Files.writeString(file, "a\nb\n");

        var editResult = editTool.execute(JSON.toJSON(Map.of("edits", List.of(
                Map.of("path", file.toString(), "loc", "append", "content", List.of("c"))
        ))));

        assertNoError(editResult.getResult());
        assertEquals("a\nb\nc\n", Files.readString(file));
    }

    @Test
    void prependToFile() throws IOException {
        Path file = tempDir.resolve("A.java");
        Files.writeString(file, "b\nc\n");

        var editResult = editTool.execute(JSON.toJSON(Map.of("edits", List.of(
                Map.of("path", file.toString(), "loc", "prepend", "content", List.of("a"))
        ))));

        assertNoError(editResult.getResult());
        assertEquals("a\nb\nc\n", Files.readString(file));
    }

    // ── HashEditFileTool: line-level append/prepend ───────────────────────────

    @Test
    void appendAfterLine() throws IOException {
        Path file = tempDir.resolve("A.java");
        Files.writeString(file, "a\nb\nc\n");

        var ref = readRef(file, 2);
        var editResult = editTool.execute(JSON.toJSON(Map.of("edits", List.of(
                Map.of("path", file.toString(),
                        "loc", Map.of("append", ref),
                        "content", List.of("inserted"))
        ))));

        assertNoError(editResult.getResult());
        assertEquals("a\nb\ninserted\nc\n", Files.readString(file));
    }

    @Test
    void prependBeforeLine() throws IOException {
        Path file = tempDir.resolve("A.java");
        Files.writeString(file, "a\nb\nc\n");

        var ref = readRef(file, 2);
        var editResult = editTool.execute(JSON.toJSON(Map.of("edits", List.of(
                Map.of("path", file.toString(),
                        "loc", Map.of("prepend", ref),
                        "content", List.of("inserted"))
        ))));

        assertNoError(editResult.getResult());
        assertEquals("a\ninserted\nb\nc\n", Files.readString(file));
    }

    // ── HashEditFileTool: multi-edit atomic ───────────────────────────────────

    @Test
    void multipleEditsToSameFile() throws IOException {
        Path file = tempDir.resolve("A.java");
        Files.writeString(file, "a\nb\nc\nd\n");

        var ref1 = readRef(file, 1);
        var ref4 = readRef(file, 4);
        var editResult = editTool.execute(JSON.toJSON(Map.of("edits", List.of(
                Map.of("path", file.toString(),
                        "loc", Map.of("range", Map.of("pos", ref1, "end", ref1)),
                        "content", List.of("A")),
                Map.of("path", file.toString(),
                        "loc", Map.of("range", Map.of("pos", ref4, "end", ref4)),
                        "content", List.of("D"))
        ))));

        assertNoError(editResult.getResult());
        assertEquals("A\nb\nc\nD\n", Files.readString(file));
    }

    // ── HashEditFileTool: delete / move ───────────────────────────────────────

    @Test
    void deleteFile() throws IOException {
        Path file = tempDir.resolve("A.java");
        Files.writeString(file, "content");

        var editResult = editTool.execute(JSON.toJSON(Map.of("edits", List.of(
                Map.of("path", file.toString(), "delete", true)
        ))));

        assertNoError(editResult.getResult());
        assertFalse(Files.exists(file));
    }

    @Test
    void moveFile() throws IOException {
        Path src = tempDir.resolve("A.java");
        Path dst = tempDir.resolve("B.java");
        Files.writeString(src, "content");

        var editResult = editTool.execute(JSON.toJSON(Map.of("edits", List.of(
                Map.of("path", src.toString(), "move", dst.toString())
        ))));

        assertNoError(editResult.getResult());
        assertFalse(Files.exists(src));
        assertTrue(Files.exists(dst));
    }

    // ── HashEditFileTool: conflict detection ──────────────────────────────────

    @Test
    void mismatchWhenFileChangedAfterRead() throws IOException {
        Path file = tempDir.resolve("A.java");
        Files.writeString(file, "line1\nline2\nline3\n");

        var ref = readRef(file, 2);
        // Human modifies file
        Files.writeString(file, "line1\nLINE2_CHANGED\nline3\n");

        var editResult = editTool.execute(JSON.toJSON(Map.of("edits", List.of(
                Map.of("path", file.toString(),
                        "loc", Map.of("range", Map.of("pos", ref, "end", ref)),
                        "content", List.of("new"))
        ))));

        assertTrue(editResult.getResult().startsWith("Edit rejected:"), editResult.getResult());
        assertTrue(editResult.getResult().contains(">>>"), "should mark changed line with >>>");
        // File must be untouched
        assertEquals("line1\nLINE2_CHANGED\nline3\n", Files.readString(file));
    }

    // ── CRLF preservation ─────────────────────────────────────────────────────

    @Test
    void crlfLineEndingsPreserved() throws IOException {
        Path file = tempDir.resolve("A.java");
        Files.write(file, "line1\r\nline2\r\nline3\r\n".getBytes(StandardCharsets.UTF_8));

        var ref = readRef(file, 2);
        var editResult = editTool.execute(JSON.toJSON(Map.of("edits", List.of(
                Map.of("path", file.toString(),
                        "loc", Map.of("range", Map.of("pos", ref, "end", ref)),
                        "content", List.of("replaced"))
        ))));

        assertNoError(editResult.getResult());
        assertEquals("line1\r\nreplaced\r\nline3\r\n",
                Files.readString(file, StandardCharsets.UTF_8));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String readRef(Path file, int lineNumber) {
        var result = readTool.execute(JSON.toJSON(Map.of("file_path", file.toString())));
        for (String line : result.getResult().split("\n")) {
            if (line.startsWith(lineNumber + "#")) {
                return line.substring(0, line.indexOf(':'));
            }
        }
        throw new IllegalStateException("Line " + lineNumber + " not found:\n" + result.getResult());
    }

    private void assertNoError(String result) {
        assertFalse(result.startsWith("Error:"), result);
        assertFalse(result.startsWith("Edit rejected:"), result);
    }
}
