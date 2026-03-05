package ai.core.cli.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands @file references in user input to inline file content.
 * Only matches paths containing '/' or a file extension (to avoid @Override, @param, emails).
 *
 * @author xander
 */
public final class FileReferenceExpander {

    private static final Pattern FILE_REF = Pattern.compile(
            "(?<=^|\\s)@([-\\w./_~]+(?:/[-\\w./_~]+|\\.[a-zA-Z]\\w{0,10}))");
    private static final long MAX_FILE_SIZE = 100 * 1024;
    private static final Path CWD = Path.of("").toAbsolutePath().normalize();

    private FileReferenceExpander() {
    }

    public static String expand(String input) {
        Matcher m = FILE_REF.matcher(input);
        if (!m.find()) return input;

        var sb = new StringBuilder();
        do {
            String token = m.group(1);
            String replacement = readFileContent(token);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        } while (m.find());
        m.appendTail(sb);
        return sb.toString();
    }

    private static String readFileContent(String token) {
        Path path;
        try {
            path = Path.of(token).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return "@" + token;
        }

        if (!path.startsWith(CWD)) {
            return "@" + token;
        }

        if (!Files.isRegularFile(path)) {
            return "@" + token;
        }

        try {
            long size = Files.size(path);
            if (size > MAX_FILE_SIZE) {
                return "@" + token + " (file too large: " + (size / 1024) + "KB)";
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            String fileName = path.getFileName().toString();
            String lang = detectLanguage(fileName);
            return "\n```" + lang + "\n" + content + "\n```\n";
        } catch (IOException e) {
            return "@" + token;
        }
    }

    private static String detectLanguage(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return "";
        return fileName.substring(dot + 1);
    }
}
