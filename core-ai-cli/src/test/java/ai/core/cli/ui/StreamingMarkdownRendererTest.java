package ai.core.cli.ui;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Simple standalone verification for StreamingMarkdownRenderer.
 * Run: java StreamingMarkdownRendererTest
 */
public class StreamingMarkdownRendererTest {
    public static void main(String[] args) {
        testDumbTerminalNoDuplicate();
        testDumbTerminalMultiLine();
        testSmartTerminalRendering();
        testCodeBlockStreaming();
        testPartialFlushDelta();
        System.out.println("All tests passed!");
    }

    private static void testDumbTerminalNoDuplicate() {
        var sw = new StringWriter();
        var renderer = new StreamingMarkdownRenderer(new PrintWriter(sw), false, 80);

        renderer.processChunk("Hel");
        renderer.processChunk("lo W");
        renderer.processChunk("orld\n");

        String output = sw.toString();
        // dumb terminal: raw delta + newline, no duplication
        assert countOccurrences(output, "Hel") == 1 : "Duplicated in dumb mode. Output: [" + escape(output) + "]";
        assert output.endsWith("\n") : "Should end with newline";
        System.out.println("[PASS] testDumbTerminalNoDuplicate");
    }

    private static void testDumbTerminalMultiLine() {
        var sw = new StringWriter();
        var renderer = new StreamingMarkdownRenderer(new PrintWriter(sw), false, 80);

        renderer.processChunk("Line 1\nLine 2\n");

        String output = sw.toString();
        assert countOccurrences(output, "Line 1") == 1 : "Line 1 duplicated. Output: [" + escape(output) + "]";
        assert countOccurrences(output, "Line 2") == 1 : "Line 2 duplicated. Output: [" + escape(output) + "]";
        System.out.println("[PASS] testDumbTerminalMultiLine");
    }

    private static void testSmartTerminalRendering() {
        var sw = new StringWriter();
        var renderer = new StreamingMarkdownRenderer(new PrintWriter(sw), true, 80);

        renderer.processChunk("# Hello\n");

        String output = sw.toString();
        String visible = simulateTerminal(output);
        assert visible.contains("Hello") : "Smart terminal should render. Visible: [" + escape(visible) + "]";
        System.out.println("[PASS] testSmartTerminalRendering");
    }

    private static void testCodeBlockStreaming() {
        var sw = new StringWriter();
        var renderer = new StreamingMarkdownRenderer(new PrintWriter(sw), false, 80);

        renderer.processChunk("```java\nint x");
        renderer.processChunk(" = 1;\n```\n");

        String output = stripAnsi(sw.toString());
        assert output.contains("int x = 1;") : "Code block content missing. Output: [" + escape(output) + "]";
        assert countOccurrences(output, "int x") == 1 : "Code duplicated. Output: [" + escape(output) + "]";
        System.out.println("[PASS] testCodeBlockStreaming");
    }

    private static void testPartialFlushDelta() {
        var sw = new StringWriter();
        var renderer = new StreamingMarkdownRenderer(new PrintWriter(sw), false, 80);

        renderer.processChunk("AB");
        String afterFirst = sw.toString();
        renderer.processChunk("CD");
        String afterSecond = sw.toString();

        assert stripAnsi(afterFirst).equals("AB") : "First chunk: [" + escape(afterFirst) + "]";
        assert stripAnsi(afterSecond).equals("ABCD") : "Second chunk: [" + escape(afterSecond) + "]";
        System.out.println("[PASS] testPartialFlushDelta");
    }

    private static String simulateTerminal(String raw) {
        var lines = new java.util.ArrayList<StringBuilder>();
        lines.add(new StringBuilder());
        int col = 0;
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            // ANSI escape: \033[...m or \033[2K etc
            if (c == '\u001B' && i + 1 < raw.length() && raw.charAt(i + 1) == '[') {
                int j = i + 2;
                while (j < raw.length() && raw.charAt(j) != 'm' && raw.charAt(j) != 'K'
                       && raw.charAt(j) != 'J' && raw.charAt(j) != 'H') {
                    j++;
                }
                if (j < raw.length()) {
                    char cmd = raw.charAt(j);
                    if (cmd == 'K') {
                        // clear line
                        StringBuilder current = lines.get(lines.size() - 1);
                        current.setLength(0);
                        col = 0;
                    }
                    i = j + 1;
                    continue;
                }
            }
            if (c == '\n') {
                lines.add(new StringBuilder());
                col = 0;
            } else if (c == '\r') {
                col = 0;
            } else {
                StringBuilder current = lines.get(lines.size() - 1);
                if (col < current.length()) {
                    current.setCharAt(col, c);
                } else {
                    while (current.length() < col) current.append(' ');
                    current.append(c);
                }
                col++;
            }
            i++;
        }
        var sb = new StringBuilder();
        for (int k = 0; k < lines.size(); k++) {
            if (k > 0) sb.append('\n');
            sb.append(lines.get(k));
        }
        return sb.toString();
    }

    private static String stripAnsi(String text) {
        return text.replaceAll("\u001B\\[[0-9;]*[a-zA-Z]", "");
    }

    private static String escape(String text) {
        return text.replace("\n", "\\n").replace("\r", "\\r");
    }

    private static int countOccurrences(String text, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
