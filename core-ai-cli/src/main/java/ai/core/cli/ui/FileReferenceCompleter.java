package ai.core.cli.ui;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * JLine Completer for @file references. Provides path completion after '@'.
 *
 * @author xander
 */
public class FileReferenceCompleter implements Completer {

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        String word = line.word();
        if (word == null || !word.startsWith("@")) {
            return;
        }
        String partial = word.substring(1);
        Path base;
        String prefix;
        if (partial.contains("/")) {
            int lastSlash = partial.lastIndexOf('/');
            String dir = partial.substring(0, lastSlash);
            prefix = partial.substring(lastSlash + 1);
            base = Path.of(dir.isEmpty() ? "/" : dir);
        } else {
            base = Path.of(".");
            prefix = partial;
        }

        if (!Files.isDirectory(base)) return;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(base)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (name.startsWith(".")) continue;
                if (!name.startsWith(prefix)) continue;

                String relative = base.equals(Path.of("."))
                        ? name
                        : base + "/" + name;
                boolean isDir = Files.isDirectory(entry);
                String suffix = isDir ? "/" : "";
                candidates.add(new Candidate(
                        "@" + relative + suffix,
                        "@" + relative + suffix,
                        null,
                        isDir ? "directory" : null,
                        null,
                        null,
                        !isDir
                ));
            }
        } catch (IOException ignored) {
            // skip completion on I/O errors
        }
    }
}
