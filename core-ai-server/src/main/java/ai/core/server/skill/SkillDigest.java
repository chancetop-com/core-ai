package ai.core.server.skill;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Content fingerprint of a skill over SKILL.md plus every resource, as raw bytes:
 * {@code sha256("SKILL.md\0" + contentBytes + Σ sorted-by-path ("\0" + path + "\0" + resourceBytes))}.
 * Resources are ordered by path (case-insensitive) so the digest is independent of upload ordering
 * and of where a resource is stored (inline document vs object storage). The CLI mirrors this
 * algorithm in {@code ai.core.cli.hub.skill.SkillDigest} with the same test vectors — never change
 * one side without the other.
 *
 * @author stephen
 */
public final class SkillDigest {
    public static String of(String content, Map<String, byte[]> resources) {
        var parts = new ArrayList<byte[]>();
        parts.add("SKILL.md\0".getBytes(StandardCharsets.UTF_8));
        parts.add(nz(content).getBytes(StandardCharsets.UTF_8));
        if (resources != null && !resources.isEmpty()) {
            var sorted = new ArrayList<>(resources.entrySet());
            sorted.sort(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER));
            for (var resource : sorted) {
                parts.add(("\0" + nz(resource.getKey()) + "\0").getBytes(StandardCharsets.UTF_8));
                parts.add(nz(resource.getValue()));
            }
        }
        return sha256(parts);
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static byte[] nz(byte[] value) {
        return value == null ? new byte[0] : value;
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
}
