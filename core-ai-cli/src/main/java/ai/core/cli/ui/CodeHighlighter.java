package ai.core.cli.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Lightweight regex-based syntax highlighter for terminal code blocks.
 * Tokenizes first, then colorizes to avoid false matches inside strings/comments.
 *
 * @author xander
 */
public final class CodeHighlighter {

    private static final Map<String, Set<String>> KEYWORDS = Map.ofEntries(
            Map.entry("java", Set.of(
                    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
                    "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
                    "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
                    "interface", "long", "native", "new", "package", "private", "protected", "public",
                    "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
                    "throw", "throws", "transient", "try", "var", "void", "volatile", "while", "yield",
                    "record", "sealed", "permits", "non-sealed")),
            Map.entry("python", Set.of(
                    "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class",
                    "continue", "def", "del", "elif", "else", "except", "finally", "for", "from", "global",
                    "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise",
                    "return", "try", "while", "with", "yield")),
            Map.entry("javascript", Set.of(
                    "async", "await", "break", "case", "catch", "class", "const", "continue", "debugger",
                    "default", "delete", "do", "else", "export", "extends", "finally", "for", "function",
                    "if", "import", "in", "instanceof", "let", "new", "of", "return", "static", "super",
                    "switch", "this", "throw", "try", "typeof", "var", "void", "while", "with", "yield")),
            Map.entry("go", Set.of(
                    "break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough",
                    "for", "func", "go", "goto", "if", "import", "interface", "map", "package", "range",
                    "return", "select", "struct", "switch", "type", "var")),
            Map.entry("rust", Set.of(
                    "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum",
                    "extern", "false", "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod",
                    "move", "mut", "pub", "ref", "return", "self", "Self", "static", "struct", "super",
                    "trait", "true", "type", "unsafe", "use", "where", "while")),
            Map.entry("sql", Stream.of(
                    "SELECT", "FROM", "WHERE", "INSERT", "UPDATE", "DELETE", "CREATE", "DROP", "ALTER",
                    "TABLE", "INDEX", "VIEW", "INTO", "VALUES", "SET", "JOIN", "LEFT", "RIGHT", "INNER",
                    "OUTER", "ON", "AND", "OR", "NOT", "NULL", "IS", "IN", "AS", "ORDER", "BY", "GROUP",
                    "HAVING", "LIMIT", "OFFSET", "UNION", "ALL", "DISTINCT", "EXISTS", "BETWEEN", "LIKE",
                    "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "CONSTRAINT", "DEFAULT", "CHECK")
                    .flatMap(kw -> Stream.of(kw, kw.toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toUnmodifiableSet())),
            Map.entry("shell", Set.of(
                    "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac",
                    "function", "return", "exit", "local", "export", "source", "alias", "unset", "readonly",
                    "declare", "typeset", "in", "select", "until")),
            Map.entry("c", Set.of(
                    "auto", "break", "case", "char", "const", "continue", "default", "do", "double", "else",
                    "enum", "extern", "float", "for", "goto", "if", "inline", "int", "long", "register",
                    "restrict", "return", "short", "signed", "sizeof", "static", "struct", "switch",
                    "typedef", "union", "unsigned", "void", "volatile", "while",
                    "class", "namespace", "template", "typename", "using", "virtual", "override", "final",
                    "public", "private", "protected", "try", "catch", "throw", "new", "delete", "nullptr",
                    "bool", "true", "false", "constexpr", "noexcept", "explicit")),
            Map.entry("yaml", Set.of("true", "false", "null", "yes", "no", "on", "off"))
    );

    private static final Map<String, String> LANGUAGE_ALIASES = Map.ofEntries(
            Map.entry("js", "javascript"), Map.entry("ts", "javascript"),
            Map.entry("typescript", "javascript"), Map.entry("jsx", "javascript"),
            Map.entry("tsx", "javascript"), Map.entry("mjs", "javascript"),
            Map.entry("py", "python"), Map.entry("rb", "python"),
            Map.entry("sh", "shell"), Map.entry("bash", "shell"),
            Map.entry("zsh", "shell"), Map.entry("fish", "shell"),
            Map.entry("cpp", "c"), Map.entry("c++", "c"),
            Map.entry("cc", "c"), Map.entry("h", "c"),
            Map.entry("hpp", "c"), Map.entry("cxx", "c"),
            Map.entry("rs", "rust"), Map.entry("yml", "yaml"),
            Map.entry("golang", "go"), Map.entry("kt", "java"),
            Map.entry("kotlin", "java"), Map.entry("scala", "java"),
            Map.entry("groovy", "java"), Map.entry("cs", "java")
    );

    private static final Pattern STRING_PATTERN = Pattern.compile(
            "\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?'''|\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'|`(?:[^`\\\\]|\\\\.)*`");
    private static final Pattern C_STYLE_COMMENT = Pattern.compile("//.*");
    private static final Pattern HASH_COMMENT = Pattern.compile("#.*");
    private static final Set<String> HASH_COMMENT_LANGS = Set.of("python", "shell");
    private static final Pattern MULTI_LINE_COMMENT_INLINE = Pattern.compile("/\\*.*?\\*/");
    private static final Pattern NUMBER_PATTERN = Pattern.compile(
            "\\b(?:0[xXbBoO][\\da-fA-F_]+|\\d[\\d_]*\\.?\\d*(?:[eE][+-]?\\d+)?[fFdDlLuU]?)\\b");
    private static final Pattern ANNOTATION_PATTERN = Pattern.compile("@\\w+");
    private static final Pattern WORD_PATTERN = Pattern.compile("\\b[A-Za-z_]\\w*\\b");
    private static final Pattern YAML_KEY_PATTERN = Pattern.compile("^(\\s*)(\\S[^:]*):(.*)");

    private record Span(int start, int end, String color) {
    }

    private CodeHighlighter() {
    }

    public static String highlight(String language, String line) {
        if (language == null || language.isEmpty()) {
            return AnsiTheme.MD_CODE_BLOCK + line + AnsiTheme.RESET;
        }
        String lang = resolveLanguage(language);
        if ("diff".equals(lang)) {
            return highlightDiff(line);
        }
        if ("json".equals(lang)) {
            return highlightJson(line);
        }
        if ("yaml".equals(lang)) {
            return highlightYaml(line);
        }
        return highlightGeneric(lang, line);
    }

    private static String resolveLanguage(String language) {
        String lower = language.toLowerCase(Locale.ROOT).trim();
        String alias = LANGUAGE_ALIASES.get(lower);
        return alias != null ? alias : lower;
    }

    private static String highlightDiff(String line) {
        if (line.startsWith("+")) {
            return AnsiTheme.SYN_DIFF_ADD + line + AnsiTheme.RESET;
        }
        if (line.startsWith("-")) {
            return AnsiTheme.SYN_DIFF_DEL + line + AnsiTheme.RESET;
        }
        if (line.startsWith("@@")) {
            return AnsiTheme.SYN_TYPE + line + AnsiTheme.RESET;
        }
        return AnsiTheme.MD_CODE_BLOCK + line + AnsiTheme.RESET;
    }

    private static String highlightJson(String line) {
        List<Span> spans = new ArrayList<>();
        collectStrings(line, spans);
        collectNumbers(line, spans);
        return applySpans(line, spans);
    }

    private static String highlightYaml(String line) {
        String trimmed = line.stripLeading();
        if (trimmed.startsWith("#")) {
            return AnsiTheme.SYN_COMMENT + line + AnsiTheme.RESET;
        }
        Matcher m = YAML_KEY_PATTERN.matcher(line);
        if (m.matches()) {
            return AnsiTheme.SYN_TYPE + m.group(1) + m.group(2) + AnsiTheme.RESET
                    + AnsiTheme.MD_CODE_BLOCK + ":" + AnsiTheme.RESET
                    + highlightYamlValue(m.group(3));
        }
        return AnsiTheme.MD_CODE_BLOCK + line + AnsiTheme.RESET;
    }

    private static String highlightYamlValue(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("\"") || trimmed.startsWith("'")) {
            return AnsiTheme.SYN_STRING + value + AnsiTheme.RESET;
        }
        if (trimmed.matches("-?\\d+\\.?\\d*")) {
            return AnsiTheme.SYN_NUMBER + value + AnsiTheme.RESET;
        }
        Set<String> yamlKw = KEYWORDS.get("yaml");
        if (yamlKw != null && yamlKw.contains(trimmed)) {
            return AnsiTheme.SYN_KEYWORD + value + AnsiTheme.RESET;
        }
        return AnsiTheme.SYN_STRING + value + AnsiTheme.RESET;
    }

    private static String highlightGeneric(String lang, String line) {
        List<Span> spans = new ArrayList<>();

        collectStrings(line, spans);
        collectComments(lang, line, spans);
        collectAnnotations(line, spans);
        collectNumbers(line, spans);
        collectKeywordsAndTypes(lang, line, spans);

        return applySpans(line, spans);
    }

    private static void collectStrings(String line, List<Span> spans) {
        Matcher m = STRING_PATTERN.matcher(line);
        while (m.find()) {
            spans.add(new Span(m.start(), m.end(), AnsiTheme.SYN_STRING));
        }
    }

    private static void collectComments(String lang, String line, List<Span> spans) {
        Matcher m = C_STYLE_COMMENT.matcher(line);
        while (m.find()) {
            if (!isOverlapping(spans, m.start(), m.end())) {
                spans.add(new Span(m.start(), m.end(), AnsiTheme.SYN_COMMENT));
            }
        }
        if (HASH_COMMENT_LANGS.contains(lang)) {
            m = HASH_COMMENT.matcher(line);
            while (m.find()) {
                if (!isOverlapping(spans, m.start(), m.end())) {
                    spans.add(new Span(m.start(), m.end(), AnsiTheme.SYN_COMMENT));
                }
            }
        }
        m = MULTI_LINE_COMMENT_INLINE.matcher(line);
        while (m.find()) {
            if (!isOverlapping(spans, m.start(), m.end())) {
                spans.add(new Span(m.start(), m.end(), AnsiTheme.SYN_COMMENT));
            }
        }
    }

    private static void collectAnnotations(String line, List<Span> spans) {
        Matcher m = ANNOTATION_PATTERN.matcher(line);
        while (m.find()) {
            if (!isOverlapping(spans, m.start(), m.end())) {
                spans.add(new Span(m.start(), m.end(), AnsiTheme.SYN_ANNOTATION));
            }
        }
    }

    private static void collectNumbers(String line, List<Span> spans) {
        Matcher m = NUMBER_PATTERN.matcher(line);
        while (m.find()) {
            if (!isOverlapping(spans, m.start(), m.end())) {
                spans.add(new Span(m.start(), m.end(), AnsiTheme.SYN_NUMBER));
            }
        }
    }

    private static void collectKeywordsAndTypes(String lang, String line, List<Span> spans) {
        Set<String> kw = KEYWORDS.get(lang);
        if (kw == null) return;

        Matcher m = WORD_PATTERN.matcher(line);
        while (m.find()) {
            if (isOverlapping(spans, m.start(), m.end())) continue;
            String word = m.group();
            if (kw.contains(word)) {
                spans.add(new Span(m.start(), m.end(), AnsiTheme.SYN_KEYWORD));
            } else if (isTypeName(word)) {
                spans.add(new Span(m.start(), m.end(), AnsiTheme.SYN_TYPE));
            }
        }
    }

    private static boolean isTypeName(String word) {
        if (word.isEmpty() || !Character.isUpperCase(word.charAt(0)) || word.length() <= 1) {
            return false;
        }
        for (int i = 1; i < word.length(); i++) {
            if (Character.isLowerCase(word.charAt(i))) return true;
        }
        return false;
    }

    private static boolean isOverlapping(List<Span> spans, int start, int end) {
        for (Span s : spans) {
            if (start < s.end && end > s.start) return true;
        }
        return false;
    }

    private static String applySpans(String line, List<Span> spans) {
        if (spans.isEmpty()) {
            return AnsiTheme.MD_CODE_BLOCK + line + AnsiTheme.RESET;
        }
        spans.sort((a, b) -> a.start - b.start);

        StringBuilder sb = new StringBuilder();
        int pos = 0;
        for (Span span : spans) {
            if (span.start > pos) {
                sb.append(AnsiTheme.MD_CODE_BLOCK).append(line, pos, span.start).append(AnsiTheme.RESET);
            }
            sb.append(span.color).append(line, span.start, span.end).append(AnsiTheme.RESET);
            pos = span.end;
        }
        if (pos < line.length()) {
            sb.append(AnsiTheme.MD_CODE_BLOCK).append(line.substring(pos)).append(AnsiTheme.RESET);
        }
        return sb.toString();
    }
}
