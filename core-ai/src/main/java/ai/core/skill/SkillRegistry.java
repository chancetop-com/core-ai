package ai.core.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central aggregation of multiple SkillProviders.
 * Merges skills by qualified name with priority-based precedence (lower priority value wins).
 *
 * @author stephen
 */
public class SkillRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkillRegistry.class);

    private final List<SkillProvider> providers = new CopyOnWriteArrayList<>();
    private volatile List<SkillMetadata> cachedSkills;
    private final Map<String, SkillProvider> skillOwnerMap = new LinkedHashMap<>();

    public void addProvider(SkillProvider provider) {
        providers.add(provider);
        providers.sort(Comparator.comparingInt(SkillProvider::priority));
        cachedSkills = null;
    }

    public List<SkillMetadata> listAll() {
        if (cachedSkills != null) return cachedSkills;

        Map<String, SkillMetadata> merged = new LinkedHashMap<>();
        for (var provider : providers) {
            try {
                for (var skill : provider.listSkills()) {
                    var key = skill.getQualifiedName();
                    if (!merged.containsKey(key)) {
                        merged.put(key, skill);
                        skillOwnerMap.put(key, provider);
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("failed to list skills from provider (priority={})", provider.priority(), e);
            }
        }
        cachedSkills = List.copyOf(merged.values());
        return cachedSkills;
    }

    public SkillMetadata find(String name) {
        var skills = listAll();
        for (var skill : skills) {
            if (skill.getQualifiedName().equals(name) || skill.getName().equals(name)) {
                return skill;
            }
        }
        return null;
    }

    public String readContent(SkillMetadata skill) throws SkillLoadException {
        if (skill.getContent() != null) {
            return skill.getContent();
        }
        var provider = skillOwnerMap.get(skill.getQualifiedName());
        if (provider == null) {
            throw new SkillLoadException("no provider found for skill: " + skill.getQualifiedName());
        }
        return provider.readContent(skill);
    }

    public String readResource(SkillMetadata skill, String resourcePath) throws SkillLoadException {
        var provider = skillOwnerMap.get(skill.getQualifiedName());
        if (provider == null) {
            throw new SkillLoadException("no provider found for skill: " + skill.getQualifiedName());
        }
        return provider.readResource(skill, resourcePath);
    }

    public void invalidateCache() {
        cachedSkills = null;
        skillOwnerMap.clear();
    }

    public List<SkillProvider> getProviders() {
        return List.copyOf(providers);
    }
}
