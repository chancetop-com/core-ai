package ai.core.server.workspace;

import java.util.Locale;

/**
 * Naming rules shared by every workspace resource: Kubernetes names and label values are
 * restricted, and the same workspace must map to the same name in the CR, the PVC and any
 * NodePort service.
 *
 * @author stephen
 */
public final class WorkspaceIds {

    /** lowercase alphanumeric only, 32 chars max; stable for the same input */
    public static String compact(String id) {
        var compact = id == null ? "" : id.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        if (compact.length() > 32) compact = compact.substring(0, 32);
        return compact.isEmpty() ? "unknown" : compact;
    }

    public static String label(String value) {
        var sanitized = value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9_.\\-]", "_");
        if (sanitized.length() > 63) sanitized = sanitized.substring(0, 63);
        sanitized = sanitized.replaceAll("^[^A-Za-z0-9]+", "");
        sanitized = sanitized.replaceAll("[^A-Za-z0-9]+$", "");
        return sanitized.isEmpty() ? "unknown" : sanitized;
    }

    private WorkspaceIds() {
    }
}
