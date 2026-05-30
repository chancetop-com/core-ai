package ai.core.cli.upgrade;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author stephen
 */
public final class VersionUtil {
    private static final Logger LOGGER = LoggerFactory.getLogger(VersionUtil.class);

    private static final String VERSION_RESOURCE = "/VERSION";

    public static String getCurrentVersion() {
        try (InputStream is = VersionUtil.class.getResourceAsStream(VERSION_RESOURCE)) {
            if (is != null) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to read version resource", e);
        }
        return "unknown";
    }

    public static int compare(String v1, String v2) {
        int[] parts1 = parse(v1);
        int[] parts2 = parse(v2);
        for (int i = 0; i < Math.min(parts1.length, parts2.length); i++) {
            int cmp = Integer.compare(parts1[i], parts2[i]);
            if (cmp != 0) return cmp;
        }
        return Integer.compare(parts1.length, parts2.length);
    }

    private static int[] parse(String version) {
        String[] parts = version.split("\\.");
        int[] nums = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            nums[i] = Integer.parseInt(parts[i].replaceAll("[^0-9]", ""));
        }
        return nums;
    }

    private VersionUtil() {
    }
}
