package ai.core.server.settings;

import ai.core.api.server.settings.SystemSettingsRequest;
import ai.core.server.domain.SystemSettings;
import core.framework.web.exception.BadRequestException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Desktop direct-conversation policy stored in system settings. The deployment still owns the
 * fallback allowlist; this class only normalizes and applies the admin's explicit choice.
 *
 * @author core-ai
 */
public final class DesktopConversationPolicy {
    public static final String MODE_INHERIT = "inherit";
    public static final String MODE_OFF = "off";
    public static final String MODE_ALL = "all";
    public static final String MODE_SELECTED = "selected";

    public static void apply(SystemSettings entity, SystemSettingsRequest request) {
        if (request.desktopDirectConversationMode == null) return;
        var mode = request.desktopDirectConversationMode.trim().toLowerCase(Locale.ROOT);
        if (!MODE_INHERIT.equals(mode) && !MODE_OFF.equals(mode)
                && !MODE_ALL.equals(mode) && !MODE_SELECTED.equals(mode)) {
            throw new BadRequestException("desktop direct conversation mode must be inherit, off, all or selected");
        }
        entity.desktopDirectConversationMode = MODE_INHERIT.equals(mode) ? null : mode;
        entity.desktopDirectConversationAgents = MODE_SELECTED.equals(mode)
                ? String.join(",", normalizeAgentIds(request.desktopDirectConversationAgents))
                : null;
    }

    public static String mode(SystemSettings entity) {
        if (entity == null || entity.desktopDirectConversationMode == null) return MODE_INHERIT;
        var mode = entity.desktopDirectConversationMode.trim().toLowerCase(Locale.ROOT);
        return MODE_OFF.equals(mode) || MODE_ALL.equals(mode) || MODE_SELECTED.equals(mode) ? mode : MODE_INHERIT;
    }

    public static List<String> agents(SystemSettings entity) {
        return entity == null ? List.of() : parseAgentIds(entity.desktopDirectConversationAgents);
    }

    private static List<String> normalizeAgentIds(List<String> values) {
        if (values == null) return List.of();
        var result = new ArrayList<String>();
        for (var value : values) {
            if (value == null) continue;
            var id = value.trim();
            if (!id.isEmpty() && !result.contains(id)) result.add(id);
        }
        return result;
    }

    private static List<String> parseAgentIds(String value) {
        if (value == null || value.isBlank()) return List.of();
        var result = new ArrayList<String>();
        for (var part : value.split(",")) {
            var id = part.trim();
            if (!id.isEmpty() && !result.contains(id)) result.add(id);
        }
        return result;
    }

    private DesktopConversationPolicy() {
    }
}
