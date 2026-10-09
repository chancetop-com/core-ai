package ai.core.cli.appserver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;

/**
 * Canonical path resolution shared by the workspace boundary checks and the attachment staging
 * validation: symlinks are resolved through the deepest existing ancestor, the non-existing
 * remainder is appended lexically, so {@code ..} and symlinks cannot fake containment.
 *
 * @author stephen
 */
public final class CanonicalPath {
    public static Path of(Path path) {
        var remainder = new ArrayDeque<String>();
        var current = path;
        while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            var name = current.getFileName();
            if (name != null) {
                remainder.push(name.toString());
            }
            current = current.getParent();
        }
        if (current == null) {
            return null;
        }
        try {
            var real = current.toRealPath();
            while (!remainder.isEmpty()) {
                real = real.resolve(remainder.pop());
            }
            return real.normalize();
        } catch (IOException e) {
            return null;
        }
    }

    public static Path ofOrNormalized(Path path) {
        var canonical = of(path);
        return canonical != null ? canonical : path.toAbsolutePath().normalize();
    }

    public static boolean isWithin(Path root, Path candidate) {
        return candidate != null && candidate.startsWith(root);
    }

    private CanonicalPath() {
    }
}
