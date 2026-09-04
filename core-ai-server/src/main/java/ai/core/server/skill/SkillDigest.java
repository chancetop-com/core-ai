package ai.core.server.skill;

import ai.core.server.domain.SkillResource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Content fingerprint of a skill over SKILL.md plus every resource:
 * {@code sha256("SKILL.md\0" + content + Σ_resources_sorted_by_path ("\0" + path + "\0" + content))}.
 * Resources are ordered by path so the digest is independent of upload ordering.
 * <p>
 * The CLI mirrors this algorithm in {@code ai.core.cli.hub.skill.SkillDigest} with the
 * same test vectors — never change one side without the other.
 *
 * @author stephen
 */
public final class SkillDigest {
    public static String of(String content, List<SkillResource> resources) {
        var bytes = new ArrayList<byte[]>();
        bytes.add("SKILL.md\0".getBytes(StandardCharsets.UTF_8));
        bytes.add(nz(content).getBytes(StandardCharsets.UTF_8));
        if (resources != null) {
            var sorted = new ArrayList<>(resources);
            sorted.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(path(a), path(b)));
            for (var resource : sorted) {
                bytes.add(("\0" + path(resource) + "\0").getBytes(StandardCharsets.UTF_8));
                bytes.add(nz(resource.content).getBytes(StandardCharsets.UTF_8));
            }
        }
        return sha256(bytes);
    }

    private static String path(SkillResource resource) {
        return resource.path == null ? "" : resource.path;
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
}
