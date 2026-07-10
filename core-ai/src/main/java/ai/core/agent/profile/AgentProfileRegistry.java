package ai.core.agent.profile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central registry for agent profiles. Aggregates multiple AgentProfileProvider
 * instances with priority-based merging — lower priority value wins.
 *
 * @author lim chen
 */
public class AgentProfileRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentProfileRegistry.class);

    private final List<AgentProfileProvider> providers = new CopyOnWriteArrayList<>();
    private volatile List<AgentProfile> cachedProfiles;

    public void addProvider(AgentProfileProvider provider) {
        providers.add(provider);
        providers.sort(Comparator.comparingInt(AgentProfileProvider::priority));
        cachedProfiles = null;
    }

    public List<AgentProfile> listAll() {
        if (cachedProfiles != null) return cachedProfiles;

        Map<String, AgentProfile> merged = new LinkedHashMap<>();
        for (var provider : providers) {
            try {
                for (var profile : provider.provide()) {
                    if (profile.name() == null || profile.name().isBlank()) {
                        LOGGER.warn("skipping agent profile with empty name from provider priority={}", provider.priority());
                        continue;
                    }
                    if (!merged.containsKey(profile.name())) {
                        merged.put(profile.name(), profile);
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("failed to list agent profiles from provider priority={}", provider.priority(), e);
            }
        }
        cachedProfiles = List.copyOf(merged.values());
        return cachedProfiles;
    }

    public Optional<AgentProfile> get(String name) {
        return listAll().stream()
                .filter(p -> p.name().equals(name))
                .findFirst();
    }

    public List<AgentProfile> listVisible() {
        return listAll();
    }

    public void invalidateCache() {
        cachedProfiles = null;
    }
}
