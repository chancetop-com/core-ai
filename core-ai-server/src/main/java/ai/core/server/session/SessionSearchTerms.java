package ai.core.server.session;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Keyword extraction and excerpt building for history search.
 *
 * <p>Chinese has no word delimiters, so a run of CJK characters is reduced to character bigrams —
 * the smallest unit that still carries meaning — while latin runs are split on punctuation and
 * filtered by a stopword list. This keeps recall without pulling in a tokenizer dependency.
 *
 * @author stephen
 */
final class SessionSearchTerms {
    private static final int MAX_TERMS = 16;
    private static final int MIN_LATIN_LENGTH = 2;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Set<String> STOPWORDS = Set.of(
        "the", "and", "for", "with", "that", "this", "what", "which", "when", "where", "who", "why", "how",
        "you", "your", "our", "their", "his", "her", "its", "was", "were", "are", "is", "be", "been",
        "have", "has", "had", "did", "does", "do", "can", "could", "should", "would", "will", "about",
        "from", "into", "out", "not", "but", "all", "any", "some", "there", "then", "than", "them", "they",
        "我们", "你们", "他们", "什么", "怎么", "为什么", "哪个", "哪里", "可以", "这个", "那个", "一下",
        "之前", "以后", "现在", "记得", "记住", "请问", "帮我", "是否", "没有", "就是", "还是", "或者",
        "因为", "所以", "如果", "但是", "而且", "然后", "已经", "还有", "关于", "一个", "这些", "那些"
    );

    static List<String> terms(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        var normalized = Normalizer.normalize(query, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        var terms = new LinkedHashSet<String>();
        var latin = new StringBuilder();
        var cjk = new StringBuilder();
        for (var codePoint : normalized.codePoints().toArray()) {
            if (isCjk(codePoint)) {
                flushLatin(latin, terms);
                cjk.appendCodePoint(codePoint);
            } else if (Character.isLetterOrDigit(codePoint)) {
                flushCjk(cjk, terms);
                latin.appendCodePoint(codePoint);
            } else {
                flushLatin(latin, terms);
                flushCjk(cjk, terms);
            }
        }
        flushLatin(latin, terms);
        flushCjk(cjk, terms);
        return terms.stream().limit(MAX_TERMS).toList();
    }

    static int matchCount(String content, List<String> terms) {
        if (content == null || content.isEmpty() || terms.isEmpty()) {
            return 0;
        }
        var lower = content.toLowerCase(Locale.ROOT);
        var count = 0;
        for (var term : terms) {
            if (lower.contains(term)) {
                count++;
            }
        }
        return count;
    }

    static String snippet(String content, List<String> terms, int radius) {
        if (content == null || content.isBlank()) {
            return "";
        }
        var text = WHITESPACE.matcher(content).replaceAll(" ").strip();
        var index = firstMatchIndex(text.toLowerCase(Locale.ROOT), terms);
        if (index < 0) {
            return truncate(text, radius * 2);
        }
        var start = Math.max(0, index - radius);
        var end = Math.min(text.length(), index + radius);
        return (start > 0 ? "…" : "") + text.substring(start, end) + (end < text.length() ? "…" : "");
    }

    private static int firstMatchIndex(String lowerContent, List<String> terms) {
        var index = -1;
        for (var term : terms) {
            var found = lowerContent.indexOf(term);
            if (found >= 0 && (index < 0 || found < index)) {
                index = found;
            }
        }
        return index;
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private static void flushLatin(StringBuilder run, Set<String> terms) {
        if (run.isEmpty()) {
            return;
        }
        var token = run.toString();
        if (token.length() >= MIN_LATIN_LENGTH && !STOPWORDS.contains(token)) {
            terms.add(token);
        }
        run.setLength(0);
    }

    private static void flushCjk(StringBuilder run, Set<String> terms) {
        if (run.isEmpty()) {
            return;
        }
        var codePoints = run.toString().codePoints().toArray();
        if (codePoints.length == 1) {
            terms.add(new String(codePoints, 0, 1));
        } else {
            for (var i = 0; i < codePoints.length - 1; i++) {
                var bigram = new String(codePoints, i, 2);
                if (!STOPWORDS.contains(bigram)) {
                    terms.add(bigram);
                }
            }
        }
        run.setLength(0);
    }

    private static boolean isCjk(int codePoint) {
        var script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
            || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL;
    }

    private SessionSearchTerms() {
    }
}
