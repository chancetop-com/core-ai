package ai.core.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Determines whether a PowerShell command is read-only / side-effect-free.
 * Whitelist-based: uncertain commands are treated as unsafe (returns false).
 */
public final class PowershellReadOnlyChecker {

    static final Set<String> READONLY = Set.of(
            "get-childitem", "dir", "ls",
            "get-content", "cat", "type", "gc",
            "get-item", "gi",
            "get-location", "pwd", "gl",
            "get-date",
            "get-process", "ps", "gps",
            "get-service", "gsv",
            "get-command", "gcm",
            "get-help", "man",
            "get-member", "gm",
            "get-variable", "gv",
            "get-filehash",
            "select-string", "sls",
            "select-object", "select",
            "where-object", "where", "?",
            "foreach-object", "foreach", "%",
            "compare-object", "diff", "compare",
            "measure-object", "measure",
            "sort-object", "sort",
            "group-object", "group",
            "format-list", "fl",
            "format-table", "ft",
            "format-wide", "fw",
            "out-string",
            "convertto-json",
            "convertto-csv",
            "test-path",
            "test-connection",
            "write-output", "echo", "write",
            "write-host",
            "write-information",
            "write-verbose",
            "write-debug",
            "write-warning",
            "tee-object", "tee"
    );

    static final Map<String, Set<String>> WRITE_FLAGS = Map.of(
            "tee-object", Set.of("-filepath", "-outputpath"),
            "sort-object", Set.of("-outvariable"),
            "group-object", Set.of("-outvariable")
    );

    static final Set<String> GIT_SAFE = Set.of(
            "status", "log", "diff", "show", "blame", "branch", "remote",
            "rev-parse", "ls-files", "tag", "describe", "config", "for-each-ref"
    );

    static final Set<String> WRAPPERS = Set.of("command", "builtin", "exec", "time", "nohup");

    static final Set<String> CONTROL_OPS = Set.of("&&", "||", ";", "|", "&");

    public static boolean isReadOnly(String command) {
        if (command == null || command.isBlank()) return false;

        String src = command.replace("2>&1", " ").replace("*>&1", " ");

        Lexer.Result lex = Lexer.tokenize(src);
        if (lex.unsafe) return false;

        List<List<String>> groups = splitByControlOps(lex.tokens);
        if (groups.isEmpty()) return false;

        for (List<String> sub : groups) {
            if (!isSingleReadOnly(sub)) return false;
        }
        return true;
    }

    static List<List<String>> splitByControlOps(List<Lexer.Tok> tokens) {
        List<List<String>> groups = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        for (Lexer.Tok t : tokens) {
            if (t instanceof Lexer.Op(String op) && CONTROL_OPS.contains(op)) {
                if (!cur.isEmpty()) {
                    groups.add(cur);
                    cur = new ArrayList<>();
                }
            } else if (t instanceof Lexer.Word(String text)) {
                cur.add(text);
            }
        }
        if (!cur.isEmpty()) groups.add(cur);
        return groups;
    }

    static boolean isSingleReadOnly(List<String> words) {
        int i = 0;
        while (i < words.size() && WRAPPERS.contains(words.get(i).toLowerCase(Locale.ENGLISH))) i++;
        if (i >= words.size()) return false;

        String raw = words.get(i);
        String cmd = basename(raw).toLowerCase(Locale.ENGLISH);
        List<String> args = words.subList(i + 1, words.size());

        if (READONLY.contains(cmd)) return !hasWriteFlag(cmd, args);
        if ("git".equals(cmd)) return isGitReadOnly(args);
        if ("docker".equals(cmd)) return isDockerReadOnly(args);

        return false;
    }

    static boolean hasWriteFlag(String cmd, List<String> args) {
        Set<String> bad = WRITE_FLAGS.get(cmd);
        if (bad == null) return false;
        return args.stream().anyMatch(a -> bad.contains(a.toLowerCase(Locale.ENGLISH)));
    }

    static boolean isGitReadOnly(List<String> args) {
        for (String a : args) {
            if ("-c".equals(a) || a.startsWith("--exec-path") || a.startsWith("--config-env"))
                return false;
        }
        return args.stream().filter(a -> !a.startsWith("-")).findFirst()
                .map(GIT_SAFE::contains).orElse(false);
    }

    static boolean isDockerReadOnly(List<String> args) {
        Set<String> safe = Set.of("logs", "inspect", "ps", "images", "version", "info");
        return args.stream().filter(a -> !a.startsWith("-")).findFirst()
                .map(safe::contains).orElse(false);
    }

    static String basename(String path) {
        int idx = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    static final class Lexer {
        @SuppressWarnings({"checkstyle:MethodLength", "checkstyle:ExecutableStatementCount"})
        static Result tokenize(String s) {
            Result res = new Result();
            StringBuilder cur = new StringBuilder();
            int n = s.length();
            int i = 0;

            while (i < n) {
                char c = s.charAt(i);

                if (Character.isWhitespace(c)) {
                    flush(res, cur);
                    i++;
                    continue;
                }
                if (c == '\\') {
                    if (i + 1 < n) {
                        cur.append(s.charAt(i + 1));
                        i += 2;
                    } else {
                        i++;
                    }
                    continue;
                }
                if (c == '\'') {
                    int j = i + 1;
                    while (j < n && s.charAt(j) != '\'') cur.append(s.charAt(j++));
                    if (j >= n) {
                        res.markUnsafe("unclosed single quote");
                        return res;
                    }
                    i = j + 1;
                    continue;
                }
                if (c == '"') {
                    int j = i + 1;
                    while (j < n && s.charAt(j) != '"') {
                        char d = s.charAt(j);
                        if (d == '\\' && j + 1 < n) {
                            cur.append(s.charAt(j + 1));
                            j += 2;
                            continue;
                        }
                        cur.append(d);
                        j++;
                    }
                    if (j >= n) {
                        res.markUnsafe("unclosed double quote");
                        return res;
                    }
                    i = j + 1;
                    continue;
                }
                if (c == '`') {
                    cur.append(c);
                    i++;
                    continue;
                }
                if (c == '$') {
                    char next = (i + 1 < n) ? s.charAt(i + 1) : '\0';
                    if (next == '(') res.markUnsafe("subexpression $()");
                    else if (next == '{') res.markUnsafe("variable expansion ${}");
                    cur.append(c);
                    i++;
                    continue;
                }
                if (c == '@') {
                    char next = (i + 1 < n) ? s.charAt(i + 1) : '\0';
                    if (next == '(') res.markUnsafe("subexpression @()");
                    cur.append(c);
                    i++;
                    continue;
                }
                if (c == '*' || c == '?') {
                    res.markUnsafe("unquoted glob");
                    cur.append(c);
                    i++;
                    continue;
                }
                if (c == '>' || c == '<') {
                    res.markUnsafe("redirection " + c);
                    i++;
                    continue;
                }

                if (c == '&') {
                    flush(res, cur);
                    if (i + 1 < n && s.charAt(i + 1) == '&') {
                        res.tokens.add(new Op("&&"));
                        i += 2;
                    } else {
                        res.tokens.add(new Op("&"));
                        i++;
                    }
                    continue;
                }
                if (c == '|') {
                    flush(res, cur);
                    if (i + 1 < n && s.charAt(i + 1) == '|') {
                        res.tokens.add(new Op("||"));
                        i += 2;
                    } else {
                        res.tokens.add(new Op("|"));
                        i++;
                    }
                    continue;
                }
                if (c == ';') {
                    flush(res, cur);
                    res.tokens.add(new Op(";"));
                    i++;
                    continue;
                }

                cur.append(c);
                i++;
            }
            flush(res, cur);
            return res;
        }

        static void flush(Result res, StringBuilder cur) {
            if (!cur.isEmpty()) {
                res.tokens.add(new Word(cur.toString()));
                cur.setLength(0);
            }
        }

        sealed interface Tok permits Word, Op { }
        record Word(String text) implements Tok { }
        record Op(String op) implements Tok { }

        static final class Result {
            final List<Tok> tokens = new ArrayList<>();
            boolean unsafe = false;
            String reason = "";

            void markUnsafe(String r) {
                unsafe = true;
                if (reason.isEmpty()) reason = r;
            }
        }
    }
}
