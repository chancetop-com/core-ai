package ai.core.server.skill.quality;

/**
 * Compares the numeric version prefix used by skill metadata. Pre-release/build suffixes are ignored for
 * ordering, matching SemVer precedence for the common cases without pulling in a version library.
 *
 * @author core-ai
 */
public final class SkillVersions {
    public static int compare(String left, String right) {
        var a = numbers(left);
        var b = numbers(right);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            var va = i < a.length ? a[i] : 0;
            var vb = i < b.length ? b[i] : 0;
            if (va != vb) return Integer.compare(va, vb);
        }
        return 0;
    }

    private static int[] numbers(String version) {
        if (version == null) return new int[0];
        var core = version.split("[-+]", 2)[0];
        var parts = core.split("\\.");
        var result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                result[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                result[i] = 0;
            }
        }
        return result;
    }

    private SkillVersions() {
    }
}
