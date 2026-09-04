package ai.core.cli.hub.skill;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * Content fingerprint of a skill over SKILL.md plus every resource:
 * {@code sha256("SKILL.md\0" + content + Σ_resources_sorted_by_path ("\0" + path + "\0" + content))}.
 * Mirrors {@code ai.core.server.skill.SkillDigest} — the two sides share the same test
 * vectors and must never drift (that is what makes "outdated" detection trustworthy).
 *
 * @author stephen
 */
public final class SkillDigest {
    public static String of(String content, List<Resource> resources) {
        var bytes = new ArrayList<byte[]>();
        bytes.add("SKILL.md\0".getBytes(StandardCharsets.UTF_8));
        bytes.add(nz(content).getBytes(StandardCharsets.UTF_8));
        if (resources != null) {
            var sorted = new ArrayList<>(resources);
            sorted.sort(Comparator.comparing(Resource::path, String.CASE_INSENSITIVE_ORDER));
            for (var resource : sorted) {
                bytes.add(("\0" + nz(resource.path()) + "\0").getBytes(StandardCharsets.UTF_8));
                bytes.add(nz(resource.content()).getBytes(StandardCharsets.UTF_8));
            }
        }
        return sha256(bytes);
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static String sha256(List<byte[]> parts) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var part : parts) digest.update(part);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private SkillDigest() {
    }

    public record Resource(String path, String content) {
    }
}
