package ai.core.cli.remote;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Searchable catalog of remote agents available for delegation.
 *
 * @author xander
 */
public class RemoteAgentCatalog {
    private final List<RemoteAgentCatalogEntry> entries;

    public RemoteAgentCatalog(List<RemoteAgentCatalogEntry> entries) {
        var byId = new LinkedHashMap<String, RemoteAgentCatalogEntry>();
        if (entries != null) {
            for (var entry : entries) {
                if (entry == null || entry.id() == null || entry.id().isBlank()) continue;
                byId.putIfAbsent(key(entry.id()), entry);
            }
        }
        this.entries = List.copyOf(byId.values());
    }

    public List<RemoteAgentCatalogEntry> entries() {
        return entries;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    public int serverCount() {
        return (int) entries.stream().map(RemoteAgentCatalogEntry::serverId).filter(v -> v != null && !v.isBlank())
                .distinct().count();
    }

    public RemoteAgentCatalogEntry find(String idOrName) {
        if (idOrName == null || idOrName.isBlank()) return null;
        var requested = key(idOrName);
        RemoteAgentCatalogEntry fallback = null;
        for (var entry : entries) {
            if (key(entry.id()).equals(requested)) return entry;
            if (same(entry.agentId(), requested) || same(entry.name(), requested)) {
                if (fallback != null) return null;
                fallback = entry;
            }
        }
        return fallback;
    }

    public List<RemoteAgentCatalogEntry> search(String query, int limit) {
        var max = limit > 0 ? limit : entries.size();
        if (query == null || query.isBlank()) return entries.stream().limit(max).toList();
        var keywords = query.toLowerCase(Locale.ROOT).split("\\s+");
        var results = new ArrayList<RemoteAgentCatalogEntry>();
        for (var entry : entries) {
            if (matches(entry, keywords)) {
                results.add(entry);
                if (results.size() >= max) break;
            }
        }
        return List.copyOf(results);
    }

    private boolean matches(RemoteAgentCatalogEntry entry, String[] keywords) {
        var text = entry.searchableText().toLowerCase(Locale.ROOT);
        for (var keyword : keywords) {
            if (!keyword.isBlank() && text.contains(keyword)) return true;
        }
        return false;
    }

    private boolean same(String value, String requested) {
        return value != null && key(value).equals(requested);
    }

    private String key(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
