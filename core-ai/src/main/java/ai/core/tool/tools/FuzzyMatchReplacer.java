package ai.core.tool.tools;

import java.util.ArrayList;
import java.util.List;

/**
 * Multi-level fuzzy matching for edit file operations.
 * Tries increasingly relaxed matching strategies to find old_string in file content.
 *
 * @author lim chen
 */
public final class FuzzyMatchReplacer {
    private static final double SINGLE_CANDIDATE_SIMILARITY_THRESHOLD = 0.0;
    private static final double MULTIPLE_CANDIDATES_SIMILARITY_THRESHOLD = 0.3;
    private static final double CONTEXT_AWARE_SIMILARITY_THRESHOLD = 0.5;

    public static List<MatchResult> findMatches(String content, String find) {
        MatchStrategy[] strategies = {
            FuzzyMatchReplacer::simpleMatch,
            FuzzyMatchReplacer::lineTrimmedMatch,
            FuzzyMatchReplacer::blockAnchorMatch,
            FuzzyMatchReplacer::whitespaceNormalizedMatch,
            FuzzyMatchReplacer::indentationFlexibleMatch,
            FuzzyMatchReplacer::trimmedBoundaryMatch,
            FuzzyMatchReplacer::contextAwareMatch
        };

        for (var strategy : strategies) {
            List<MatchResult> results = strategy.find(content, find);
            if (!results.isEmpty()) {
                return results;
            }
        }
        return List.of();
    }

    static List<MatchResult> simpleMatch(String content, String find) {
        List<MatchResult> results = new ArrayList<>();
        int index = content.indexOf(find, 0);
        while (index != -1) {
            results.add(new MatchResult(find, "exact"));
            index = content.indexOf(find, index + find.length());
        }
        return results;
    }

    static List<MatchResult> lineTrimmedMatch(String content, String find) {
        String[] originalLines = content.split("\n", -1);
        String[] searchLines = find.split("\n", -1);

        int searchLen = trimTrailingEmptyLine(searchLines);
        if (searchLen == 0) return List.of();

        List<MatchResult> results = new ArrayList<>();
        for (int i = 0; i <= originalLines.length - searchLen; i++) {
            if (matchesTrimmedLines(originalLines, searchLines, i, searchLen)) {
                results.add(new MatchResult(extractBlock(originalLines, i, i + searchLen - 1), "line_trimmed"));
            }
        }
        return results;
    }

    static List<MatchResult> blockAnchorMatch(String content, String find) {
        String[] originalLines = content.split("\n", -1);
        String[] searchLines = find.split("\n", -1);

        int searchLen = trimTrailingEmptyLine(searchLines);
        if (searchLen < 3) return List.of();

        String firstLineSearch = searchLines[0].trim();
        String lastLineSearch = searchLines[searchLen - 1].trim();

        List<int[]> candidates = collectAnchorCandidates(originalLines, firstLineSearch, lastLineSearch);
        if (candidates.isEmpty()) return List.of();

        if (candidates.size() == 1) {
            int startLine = candidates.get(0)[0];
            int endLine = candidates.get(0)[1];
            int actualBlockSize = endLine - startLine + 1;
            double similarity = computeMiddleSimilarity(originalLines, searchLines, startLine, searchLen, actualBlockSize);
            if (similarity >= SINGLE_CANDIDATE_SIMILARITY_THRESHOLD) {
                return List.of(new MatchResult(extractBlock(originalLines, startLine, endLine), "block_anchor"));
            }
            return List.of();
        }

        return pickBestCandidate(originalLines, searchLines, candidates, searchLen);
    }

    static List<MatchResult> whitespaceNormalizedMatch(String content, String find) {
        String normalizedFind = normalizeWhitespace(find);
        String[] lines = content.split("\n", -1);
        List<MatchResult> results = new ArrayList<>();

        for (String line : lines) {
            if (normalizeWhitespace(line).equals(normalizedFind)) {
                results.add(new MatchResult(line, "whitespace_normalized"));
            }
        }

        String[] findLines = find.split("\n", -1);
        if (findLines.length > 1) {
            for (int i = 0; i <= lines.length - findLines.length; i++) {
                String block = extractBlock(lines, i, i + findLines.length - 1);
                if (normalizeWhitespace(block).equals(normalizedFind)) {
                    results.add(new MatchResult(block, "whitespace_normalized"));
                }
            }
        }
        return results;
    }

    static List<MatchResult> indentationFlexibleMatch(String content, String find) {
        String normalizedFind = removeIndentation(find);
        String[] contentLines = content.split("\n", -1);
        String[] findLines = find.split("\n", -1);

        List<MatchResult> results = new ArrayList<>();
        for (int i = 0; i <= contentLines.length - findLines.length; i++) {
            String block = extractBlock(contentLines, i, i + findLines.length - 1);
            if (removeIndentation(block).equals(normalizedFind)) {
                results.add(new MatchResult(block, "indentation_flexible"));
            }
        }
        return results;
    }

    static List<MatchResult> trimmedBoundaryMatch(String content, String find) {
        String trimmedFind = find.trim();
        if (trimmedFind.equals(find)) return List.of();

        List<MatchResult> results = new ArrayList<>();
        int index = content.indexOf(trimmedFind, 0);
        while (index != -1) {
            results.add(new MatchResult(trimmedFind, "trimmed_boundary"));
            index = content.indexOf(trimmedFind, index + trimmedFind.length());
        }

        if (results.isEmpty()) {
            String[] lines = content.split("\n", -1);
            String[] findLines = find.split("\n", -1);
            for (int i = 0; i <= lines.length - findLines.length; i++) {
                String block = extractBlock(lines, i, i + findLines.length - 1);
                if (block.trim().equals(trimmedFind)) {
                    results.add(new MatchResult(block, "trimmed_boundary"));
                }
            }
        }
        return results;
    }

    static List<MatchResult> contextAwareMatch(String content, String find) {
        String[] findLines = find.split("\n", -1);
        int findLen = trimTrailingEmptyLine(findLines);
        if (findLen < 3) return List.of();

        String[] contentLines = content.split("\n", -1);
        String firstLine = findLines[0].trim();
        String lastLine = findLines[findLen - 1].trim();

        List<MatchResult> results = new ArrayList<>();
        for (int i = 0; i < contentLines.length; i++) {
            if (!contentLines[i].trim().equals(firstLine)) continue;
            MatchResult match = tryContextAwareBlock(contentLines, findLines, i, findLen, lastLine);
            if (match != null) {
                results.add(match);
            }
        }
        return results;
    }

    static int levenshtein(String a, String b) {
        if (a.isEmpty()) return b.length();
        if (b.isEmpty()) return a.length();

        int[][] matrix = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) matrix[i][0] = i;
        for (int j = 0; j <= b.length(); j++) matrix[0][j] = j;

        for (int i = 1; i <= a.length(); i++) {
            computeLevenshteinRow(matrix, a, b, i);
        }
        return matrix[a.length()][b.length()];
    }

    private static void computeLevenshteinRow(int[][] matrix, String a, String b, int i) {
        for (int j = 1; j <= b.length(); j++) {
            int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
            matrix[i][j] = Math.min(Math.min(
                matrix[i - 1][j] + 1,
                matrix[i][j - 1] + 1),
                matrix[i - 1][j - 1] + cost);
        }
    }

    private static int trimTrailingEmptyLine(String[] lines) {
        int len = lines.length;
        if (len > 0 && lines[len - 1].isEmpty()) {
            len--;
        }
        return len;
    }

    private static boolean matchesTrimmedLines(String[] originalLines, String[] searchLines, int startIndex, int searchLen) {
        for (int j = 0; j < searchLen; j++) {
            if (!originalLines[startIndex + j].trim().equals(searchLines[j].trim())) {
                return false;
            }
        }
        return true;
    }

    private static List<int[]> collectAnchorCandidates(String[] originalLines, String firstLineSearch, String lastLineSearch) {
        List<int[]> candidates = new ArrayList<>();
        for (int i = 0; i < originalLines.length; i++) {
            if (!originalLines[i].trim().equals(firstLineSearch)) continue;
            int endLine = findLastLineAnchor(originalLines, i + 2, lastLineSearch);
            if (endLine >= 0) {
                candidates.add(new int[]{i, endLine});
            }
        }
        return candidates;
    }

    private static int findLastLineAnchor(String[] lines, int startFrom, String lastLineSearch) {
        for (int j = startFrom; j < lines.length; j++) {
            if (lines[j].trim().equals(lastLineSearch)) {
                return j;
            }
        }
        return -1;
    }

    private static List<MatchResult> pickBestCandidate(String[] originalLines, String[] searchLines, List<int[]> candidates, int searchLen) {
        int[] bestCandidate = null;
        double maxSimilarity = -1;
        for (int[] candidate : candidates) {
            int startLine = candidate[0];
            int endLine = candidate[1];
            int actualBlockSize = endLine - startLine + 1;
            double similarity = computeMiddleSimilarity(originalLines, searchLines, startLine, searchLen, actualBlockSize);
            if (similarity > maxSimilarity) {
                maxSimilarity = similarity;
                bestCandidate = candidate;
            }
        }

        if (maxSimilarity >= MULTIPLE_CANDIDATES_SIMILARITY_THRESHOLD && bestCandidate != null) {
            return List.of(new MatchResult(extractBlock(originalLines, bestCandidate[0], bestCandidate[1]), "block_anchor"));
        }
        return List.of();
    }

    private static MatchResult tryContextAwareBlock(String[] contentLines, String[] findLines, int startIdx, int findLen, String lastLine) {
        int endIdx = findFirstAnchor(contentLines, startIdx + 2, lastLine);
        if (endIdx < 0) return null;

        int blockLen = endIdx - startIdx + 1;
        if (blockLen != findLen) return null;

        if (isContextSimilarEnough(contentLines, findLines, startIdx, blockLen)) {
            return new MatchResult(extractBlock(contentLines, startIdx, endIdx), "context_aware");
        }
        return null;
    }

    private static int findFirstAnchor(String[] lines, int startFrom, String anchor) {
        for (int j = startFrom; j < lines.length; j++) {
            if (lines[j].trim().equals(anchor)) {
                return j;
            }
        }
        return -1;
    }

    private static boolean isContextSimilarEnough(String[] contentLines, String[] findLines, int startIdx, int blockLen) {
        int matchingLines = 0;
        int totalNonEmptyLines = 0;
        for (int k = 1; k < blockLen - 1; k++) {
            String blockLine = contentLines[startIdx + k].trim();
            String findLine = findLines[k].trim();
            if (!blockLine.isEmpty() || !findLine.isEmpty()) {
                totalNonEmptyLines++;
                if (blockLine.equals(findLine)) matchingLines++;
            }
        }
        return totalNonEmptyLines == 0 || (double) matchingLines / totalNonEmptyLines >= CONTEXT_AWARE_SIMILARITY_THRESHOLD;
    }

    private static double computeMiddleSimilarity(String[] originalLines, String[] searchLines, int startLine, int searchLen, int actualBlockSize) {
        int linesToCheck = Math.min(searchLen - 2, actualBlockSize - 2);
        if (linesToCheck <= 0) return 1.0;

        double similarity = 0;
        for (int j = 1; j < searchLen - 1 && j < actualBlockSize - 1; j++) {
            String originalLine = originalLines[startLine + j].trim();
            String searchLine = searchLines[j].trim();
            int maxLen = Math.max(originalLine.length(), searchLine.length());
            if (maxLen == 0) continue;
            int distance = levenshtein(originalLine, searchLine);
            similarity += (1.0 - (double) distance / maxLen) / linesToCheck;
        }
        return similarity;
    }

    private static String extractBlock(String[] lines, int startLine, int endLine) {
        var sb = new StringBuilder();
        for (int k = startLine; k <= endLine; k++) {
            if (k > startLine) sb.append('\n');
            sb.append(lines[k]);
        }
        return sb.toString();
    }

    private static String normalizeWhitespace(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    private static String removeIndentation(String text) {
        String[] lines = text.split("\n", -1);
        int minIndent = computeMinIndent(lines);
        if (minIndent == Integer.MAX_VALUE || minIndent == 0) return text;

        var sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append('\n');
            if (lines[i].isBlank()) {
                sb.append(lines[i]);
            } else {
                sb.append(lines[i].substring(minIndent));
            }
        }
        return sb.toString();
    }

    private static int computeMinIndent(String[] lines) {
        int minIndent = Integer.MAX_VALUE;
        for (String line : lines) {
            if (line.isBlank()) continue;
            int indent = 0;
            while (indent < line.length() && Character.isWhitespace(line.charAt(indent))) indent++;
            minIndent = Math.min(minIndent, indent);
        }
        return minIndent;
    }

    private FuzzyMatchReplacer() {
    }

    @FunctionalInterface
    interface MatchStrategy {
        List<MatchResult> find(String content, String find);
    }

    public static class MatchResult {
        public final String matched;
        public final String strategyName;

        MatchResult(String matched, String strategyName) {
            this.matched = matched;
            this.strategyName = strategyName;
        }
    }
}
