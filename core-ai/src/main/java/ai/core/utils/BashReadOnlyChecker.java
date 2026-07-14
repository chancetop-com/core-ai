package ai.core.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Determines whether a bash command is read-only / side-effect-free.
 * Whitelist-based: uncertain commands are treated as unsafe (returns false).
 */
public final class BashReadOnlyChecker {

    static final Set<String> READONLY = Set.of(
            "ls", "cat", "head", "tail", "wc", "stat", "file", "nl", "od", "hexdump",
            "pwd", "whoami", "id", "echo", "date", "uname", "hostname", "printenv",
            "cut", "tr", "sort", "uniq", "column", "tac", "rev", "comm", "diff",
            "grep", "egrep", "fgrep", "rg", "find", "fd", "which", "type",
            "basename", "dirname", "df", "du", "free", "ps", "tree", "less", "more"
    );

    static final Map<String, Set<String>> WRITE_FLAGS = Map.of(
            "sort", Set.of("-o", "--output"),
            "tree", Set.of("-o", "-R"),
            "date", Set.of("-s", "--set"),
            "find", Set.of("-delete", "-exec", "-execdir", "-ok", "-okdir", "-fprint", "-fls")
    );

    static final Set<String> GIT_SAFE = Set.of(
            "status", "log", "diff", "show", "blame", "branch", "remote",
            "rev-parse", "ls-files", "tag", "describe", "config", "for-each-ref"
    );

    static final Set<String> WRAPPERS = Set.of("command", "builtin", "exec", "time", "nohup");

    static final Set<String> CONTROL_OPS = Set.of("&&", "||", ";", "|", "&");

    public static boolean isReadOnly(String command) {
        if (command == null || command.isBlank()) return false;

        String src = command.replace("2>&1", " ");

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
        for (String word : words) {
            if (!WRAPPERS.contains(word)) break;
            i++;
        }
        if (i >= words.size()) return false;

        String cmd = basename(words.get(i));
        List<String> args = words.subList(i + 1, words.size());

        if (READONLY.contains(cmd)) return !hasWriteFlag(cmd, args);
        if ("git".equals(cmd)) return isGitReadOnly(args);
        if ("sed".equals(cmd)) return args.stream().noneMatch(a -> "-i".equals(a) || a.startsWith("-i"));
        if ("docker".equals(cmd)) return isDockerReadOnly(args);

        return false;
    }

    static boolean hasWriteFlag(String cmd, List<String> args) {
        Set<String> bad = WRITE_FLAGS.get(cmd);
        return bad != null && args.stream().anyMatch(bad::contains);
    }

    static boolean isGitReadOnly(List<String> args) {
        for (String a : args) {
            if ("-c".equals(a) || a.startsWith("--exec-path") || a.startsWith("--config-env"))
                return false;
        }
        return args.stream().filter(a -> !a.startsWith("-")).findFirst()
                .map(GIT_SAFE::contains).orElse(Boolean.FALSE);
    }

    static boolean isDockerReadOnly(List<String> args) {
        Set<String> safe = Set.of("logs", "inspect", "ps", "images", "version", "info");
        return args.stream().filter(a -> !a.startsWith("-")).findFirst()
                .map(safe::contains).orElse(Boolean.FALSE);
    }

    static String basename(String path) {
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    static final class Lexer {

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
                    i = handleBackslash(s, cur, i);
                    continue;
                }
                if (c == '\'') {
                    i = readSingleQuoted(s, cur, res, i);
                    if (res.unsafe) return res;
                    continue;
                }
                if (c == '"') {
                    i = readDoubleQuoted(s, cur, res, i);
                    if (res.unsafe) return res;
                    continue;
                }
                if (c == '`') {
                    res.markUnsafe("command substitution backticks");
                    i++;
                    continue;
                }
                if (c == '$') {
                    i = handleVariable(s, res, cur, i);
                    continue;
                }
                if (c == '*' || c == '?' || c == '[' || c == ']' || c == '>' || c == '<') {
                    i = handleGlobOrRedirect(res, cur, i, c);
                    continue;
                }
                if (c == '&' || c == '|' || c == ';') {
                    i = consumeControlOp(s, res, cur, i, c);
                    continue;
                }

                cur.append(c);
                i++;
            }
            flush(res, cur);
            return res;
        }

        private static int handleBackslash(String s, StringBuilder cur, int i) {
            if (i + 1 < s.length()) {
                cur.append(s.charAt(i + 1));
                return i + 2;
            }
            return i + 1;
        }

        private static int readSingleQuoted(String s, StringBuilder cur, Result res, int i) {
            int j = i + 1;
            int n = s.length();
            while (j < n && s.charAt(j) != '\'') cur.append(s.charAt(j++));
            if (j >= n) {
                res.markUnsafe("unclosed single quote");
                return j;
            }
            return j + 1;
        }

        private static int readDoubleQuoted(String s, StringBuilder cur, Result res, int i) {
            int j = i + 1;
            int n = s.length();
            while (j < n && s.charAt(j) != '"') {
                char d = s.charAt(j);
                if (d == '\\' && j + 1 < n) {
                    cur.append(s.charAt(j + 1));
                    j += 2;
                    continue;
                }
                if (d == '$') res.markUnsafe("variable expansion in double quotes");
                cur.append(d);
                j++;
            }
            if (j >= n) {
                res.markUnsafe("unclosed double quote");
                return j;
            }
            return j + 1;
        }

        private static int handleVariable(String s, Result res, StringBuilder cur, int i) {
            char next = (i + 1 < s.length()) ? s.charAt(i + 1) : '\0';
            if (next == '(') res.markUnsafe("command substitution $()");
            else if (next == '{') res.markUnsafe("parameter expansion ${}");
            else if (isVarStart(next)) res.markUnsafe("variable expansion $VAR");
            cur.append('$');
            return i + 1;
        }

        private static int handleGlobOrRedirect(Result res, StringBuilder cur, int i, char c) {
            if (c == '*' || c == '?' || c == '[' || c == ']')
                res.markUnsafe("unquoted glob");
            else
                res.markUnsafe("redirection " + c);
            cur.append(c);
            return i + 1;
        }

        private static int consumeControlOp(String s, Result res, StringBuilder cur, int i, char c) {
            flush(res, cur);
            if (c == ';') {
                res.tokens.add(new Op(";"));
                return i + 1;
            }
            if (i + 1 < s.length() && s.charAt(i + 1) == c) {
                res.tokens.add(new Op(new String(new char[]{c, c})));
                return i + 2;
            }
            res.tokens.add(new Op(String.valueOf(c)));
            return i + 1;
        }

        static void flush(Result res, StringBuilder cur) {
            if (!cur.isEmpty()) {
                res.tokens.add(new Word(cur.toString()));
                cur.setLength(0);
            }
        }

        static boolean isVarStart(char c) {
            return c == '_' || c == '@' || c == '*' || c == '#' || c == '?' || c == '!' || c == '-' || c == '$'
                    || Character.isLetterOrDigit(c);
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
