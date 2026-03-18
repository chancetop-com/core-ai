package ai.core.tool;

import java.util.ArrayList;
import java.util.List;

/**
 * @author lim chen
 */
public final class DiffGenerator {
    private static final int CONTEXT_LINES = 3;
    private static final int MAX_LINES = 2000;
    private static final int MAX_DISPLAY_LINES = 80;

    public static DiffResult forEdit(String filePath, String content, String oldString, String newString) {
        int pos = content.indexOf(oldString);
        if (pos < 0) return null;

        String[] allLines = content.split("\n", -1);
        String[] oldLines = oldString.split("\n", -1);
        String[] newLines = newString.split("\n", -1);

        int startLine = content.substring(0, pos).split("\n", -1).length - 1;
        int endLine = startLine + oldLines.length;

        int ctxStart = Math.max(0, startLine - CONTEXT_LINES);
        int ctxEnd = Math.min(allLines.length, endLine + CONTEXT_LINES);

        var lines = new ArrayList<DisplayLine>();

        for (int i = ctxStart; i < startLine; i++) {
            lines.add(new DisplayLine(i + 1, Tag.EQUAL, allLines[i]));
        }
        for (int i = 0; i < oldLines.length; i++) {
            lines.add(new DisplayLine(startLine + i + 1, Tag.DELETE, oldLines[i]));
        }
        int newLineNum = startLine + 1;
        for (int i = 0; i < newLines.length; i++) {
            lines.add(new DisplayLine(newLineNum + i, Tag.INSERT, newLines[i]));
        }
        int newCtxStart = startLine + newLines.length;
        for (int i = endLine; i < ctxEnd; i++) {
            lines.add(new DisplayLine(newCtxStart + i - endLine + 1, Tag.EQUAL, allLines[i]));
        }

        return new DiffResult(newLines.length, oldLines.length, lines);
    }

    public static DiffResult forWrite(String filePath, String oldContent, String newContent) {
        if (oldContent == null || oldContent.isEmpty()) {
            return forNewFile(newContent);
        }

        String[] oldLines = oldContent.split("\n", -1);
        String[] newLines = newContent.split("\n", -1);

        if (oldLines.length > MAX_LINES || newLines.length > MAX_LINES) {
            return null;
        }

        List<DiffEntry> diff = computeDiff(oldLines, newLines);
        return toDiffResult(diff);
    }

    private static DiffResult forNewFile(String content) {
        if (content == null || content.isEmpty()) {
            return new DiffResult(0, 0, List.of());
        }
        String[] lines = content.split("\n", -1);
        var displayLines = new ArrayList<DisplayLine>();
        int limit = Math.min(lines.length, MAX_DISPLAY_LINES);
        for (int i = 0; i < limit; i++) {
            displayLines.add(new DisplayLine(i + 1, Tag.INSERT, lines[i]));
        }
        return new DiffResult(lines.length, 0, displayLines);
    }

    static List<DiffEntry> computeDiff(String[] oldLines, String[] newLines) {
        int n = oldLines.length;
        int m = newLines.length;

        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                if (oldLines[i].equals(newLines[j])) {
                    dp[i][j] = dp[i + 1][j + 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
                }
            }
        }

        var result = new ArrayList<DiffEntry>();
        int i = 0;
        int j = 0;
        while (i < n || j < m) {
            if (i < n && j < m && oldLines[i].equals(newLines[j])) {
                result.add(new DiffEntry(Tag.EQUAL, oldLines[i], i, j));
                i++;
                j++;
            } else if (i < n && (j >= m || dp[i + 1][j] >= dp[i][j + 1])) {
                result.add(new DiffEntry(Tag.DELETE, oldLines[i], i, j));
                i++;
            } else {
                result.add(new DiffEntry(Tag.INSERT, newLines[j], i, j));
                j++;
            }
        }
        return result;
    }

    private static DiffResult toDiffResult(List<DiffEntry> diff) {
        List<int[]> changeRanges = collectChangeRanges(diff);
        if (changeRanges.isEmpty()) return null;

        List<int[]> hunks = mergeRanges(changeRanges, diff.size());

        int additions = 0;
        int deletions = 0;
        var displayLines = new ArrayList<DisplayLine>();
        int totalLines = 0;

        for (int[] hunk : hunks) {
            for (int i = hunk[0]; i < hunk[1] && totalLines < MAX_DISPLAY_LINES; i++) {
                DiffEntry entry = diff.get(i);
                int lineNum = switch (entry.tag) {
                    case DELETE -> entry.oldIdx + 1;
                    case INSERT -> entry.newIdx + 1;
                    case EQUAL -> entry.newIdx + 1;
                };
                displayLines.add(new DisplayLine(lineNum, entry.tag, entry.content));
                if (entry.tag == Tag.INSERT) additions++;
                if (entry.tag == Tag.DELETE) deletions++;
                totalLines++;
            }
        }

        return new DiffResult(additions, deletions, displayLines);
    }

    private static List<int[]> collectChangeRanges(List<DiffEntry> diff) {
        var changeRanges = new ArrayList<int[]>();
        int idx = 0;
        while (idx < diff.size()) {
            if (diff.get(idx).tag != Tag.EQUAL) {
                int start = idx;
                while (idx < diff.size() && diff.get(idx).tag != Tag.EQUAL) idx++;
                changeRanges.add(new int[]{start, idx});
            } else {
                idx++;
            }
        }
        return changeRanges;
    }

    private static List<int[]> mergeRanges(List<int[]> changeRanges, int totalSize) {
        var hunks = new ArrayList<int[]>();
        for (int[] range : changeRanges) {
            int start = Math.max(0, range[0] - CONTEXT_LINES);
            int end = Math.min(totalSize, range[1] + CONTEXT_LINES);
            if (!hunks.isEmpty() && start <= hunks.getLast()[1]) {
                hunks.getLast()[1] = end;
            } else {
                hunks.add(new int[]{start, end});
            }
        }
        return hunks;
    }

    private DiffGenerator() {
    }

    public enum Tag { EQUAL, DELETE, INSERT }

    public record DisplayLine(int lineNumber, Tag tag, String content) {
    }

    public record DiffResult(int additions, int deletions, List<DisplayLine> lines) {
        public String serialize() {
            var sb = new StringBuilder();
            sb.append('+').append(additions).append(",-").append(deletions).append('\n');
            for (var line : lines) {
                char tag = switch (line.tag) {
                    case EQUAL -> ' ';
                    case DELETE -> '-';
                    case INSERT -> '+';
                };
                sb.append(tag).append(line.lineNumber).append('\t').append(line.content).append('\n');
            }
            return sb.toString();
        }

        public static DiffResult deserialize(String data) {
            if (data == null || data.isBlank()) return null;
            String[] rawLines = data.split("\n");
            if (rawLines.length == 0) return null;

            String header = rawLines[0];
            int commaIdx = header.indexOf(",-");
            int additions = Integer.parseInt(header.substring(1, commaIdx));
            int deletions = Integer.parseInt(header.substring(commaIdx + 2));

            var lines = new ArrayList<DisplayLine>();
            for (int i = 1; i < rawLines.length; i++) {
                String raw = rawLines[i];
                if (raw.isEmpty()) continue;
                char tagChar = raw.charAt(0);
                Tag tag = switch (tagChar) {
                    case '-' -> Tag.DELETE;
                    case '+' -> Tag.INSERT;
                    default -> Tag.EQUAL;
                };
                int tabIdx = raw.indexOf('\t');
                int lineNum = Integer.parseInt(raw.substring(1, tabIdx));
                String content = tabIdx + 1 < raw.length() ? raw.substring(tabIdx + 1) : "";
                lines.add(new DisplayLine(lineNum, tag, content));
            }
            return new DiffResult(additions, deletions, lines);
        }
    }

    record DiffEntry(Tag tag, String content, int oldIdx, int newIdx) {
    }
}
