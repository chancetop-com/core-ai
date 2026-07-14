package ai.core.server.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Normalizes Mongo id arrays accepted from APIs or older stored documents.
 */
public final class IdLists {
    public static List<String> clean(List<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        Set<String> cleaned = new LinkedHashSet<>(ids.size());
        for (var id : ids) {
            if (id == null) continue;
            var cleanId = id.trim();
            if (cleanId.isEmpty()) continue;
            cleaned.add(cleanId);
        }
        return new ArrayList<>(cleaned);
    }

    public static List<String> cleanOrNull(List<String> ids) {
        var cleaned = clean(ids);
        return cleaned.isEmpty() ? null : cleaned;
    }

    private IdLists() {
    }
}
