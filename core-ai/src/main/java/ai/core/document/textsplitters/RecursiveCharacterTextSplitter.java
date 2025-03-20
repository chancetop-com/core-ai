package ai.core.document.textsplitters;

import ai.core.document.TextChunk;
import ai.core.document.TextSplitter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * @author stephen
 */
public class RecursiveCharacterTextSplitter implements TextSplitter {
    public static final int DEFAULT_TOKEN_CHARACTERS = 5;
    public static final int DEFAULT_CODE_BLOCK_LINES = 80;
    public static final int DEFAULT_CODE_LINE_TOKENS = 10;
    public static final int DEFAULT_CODE_OVERLAP_LINES = 16;
    public static final int DEFAULT_CODING_CHUNK_SIZE = DEFAULT_CODE_BLOCK_LINES * DEFAULT_CODE_LINE_TOKENS * DEFAULT_TOKEN_CHARACTERS;
    public static final int DEFAULT_CODING_CHUNK_OVERLAP = DEFAULT_CODE_OVERLAP_LINES * DEFAULT_CODE_LINE_TOKENS * DEFAULT_TOKEN_CHARACTERS;

    public static RecursiveCharacterTextSplitter fromLanguage(LanguageSeparators.Language language) {
        return new RecursiveCharacterTextSplitter(LanguageSeparators.getSeparatorsForLanguage(language), DEFAULT_CODING_CHUNK_SIZE, DEFAULT_CODING_CHUNK_OVERLAP, true, true);
    }

    private static List<String> getDefaultSeparators() {
        return List.of("\n\n", "\n", " ", "");
    }

    private final List<String> separators;
    private final int chunkSize;
    private final int chunkOverlap;
    private final boolean keepSeparator;
    private final boolean isSeparatorRegex;

    public RecursiveCharacterTextSplitter() {
        this.separators = getDefaultSeparators();
        this.chunkSize = DEFAULT_CODING_CHUNK_SIZE;
        this.chunkOverlap = DEFAULT_CODING_CHUNK_OVERLAP;
        this.keepSeparator = true;
        this.isSeparatorRegex = false;
    }

    public RecursiveCharacterTextSplitter(List<String> separators, int chunkSize, int chunkOverlap, boolean keepSeparator, boolean isSeparatorRegex) {
        this.separators = separators != null ? separators : getDefaultSeparators();
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
        this.keepSeparator = keepSeparator;
        this.isSeparatorRegex = isSeparatorRegex;
    }

    @Override
    public List<TextChunk> split(String text) {
        return split(text, this.separators).stream().map(TextChunk::new).toList();
    }

    private List<String> split(String text, List<String> separators) {
        var finalChunks = new ArrayList<String>();
        if (text.isEmpty()) return finalChunks;

        var separator = findAppropriateSeparator(text, separators);
        var splits = splitWithSeparator(text, separator);

        var goodSplits = new ArrayList<String>();
        var separatorStr = keepSeparator ? separator : "";

        for (var s : splits) {
            if (s.length() < chunkSize) {
                goodSplits.add(s);
            } else {
                if (!goodSplits.isEmpty()) {
                    finalChunks.addAll(mergeSplits(goodSplits, separatorStr));
                    goodSplits.clear();
                }
                var remainingSeparators = separators.subList(separators.indexOf(separator) + 1, separators.size());
                if (remainingSeparators.isEmpty()) {
                    finalChunks.add(s);
                } else {
                    finalChunks.addAll(split(s, remainingSeparators));
                }
            }
        }

        if (!goodSplits.isEmpty()) {
            finalChunks.addAll(mergeSplits(goodSplits, separatorStr));
        }

        return finalChunks;
    }

    private String findAppropriateSeparator(String text, List<String> separators) {
        for (var separator : separators) {
            var escaped = isSeparatorRegex ? separator : Pattern.quote(separator);
            if (Pattern.compile(escaped).matcher(text).find()) {
                return separator;
            }
        }
        return separators.getLast();
    }

    private List<String> splitWithSeparator(String text, String separator) {
        var escaped = isSeparatorRegex ? separator : Pattern.quote(separator);
        var pattern = keepSeparator ? Pattern.compile("(" + escaped + ")") : Pattern.compile(escaped);
        var matcher = pattern.matcher(text);

        var splits = new ArrayList<String>();
        int start = 0;
        while (matcher.find()) {
            var split = text.substring(start, matcher.start());
            if (!split.isEmpty()) splits.add(split);
            if (keepSeparator) splits.add(matcher.group());
            start = matcher.end();
        }

        var remaining = text.substring(start);
        if (!remaining.isEmpty()) splits.add(remaining);

        return splits;
    }

    private List<String> mergeSplits(List<String> splits, String separator) {
        var merged = new ArrayList<String>();
        var chunk = new StringBuilder();
        for (var index = 0; index < splits.size(); index++) {
            var s = splits.get(index);
            if (!chunk.isEmpty() && chunk.length() + s.length() + separator.length() > chunkSize) {
                merged.add(chunk.toString());
                chunk.setLength(0);
                if (chunkOverlap > 0) {
                    chunk.append(getOverlapSplits(splits, index));
                }
            }
            chunk.append(s);
        }
        if (!chunk.isEmpty()) {
            merged.add(chunk.toString());
        }
        return merged;
    }

    private String getOverlapSplits(List<String> splits, int index) {
        var overlap = new StringBuilder();
        for (var backwardIndex = index - 1; backwardIndex >= 0; backwardIndex--) {
            var s = splits.get(backwardIndex);
            if (overlap.length() + s.length() >= chunkOverlap) break;
            overlap.insert(0, s);
        }
        return overlap.toString();
    }
}
