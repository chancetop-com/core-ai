package ai.core.server.skill;

import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.SkillResource;
import ai.core.skill.SkillLoadException;
import ai.core.skill.SkillMetadata;
import ai.core.skill.SkillProvider;
import core.framework.inject.Inject;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Skill source backed by Mongo. Does NOT implement SkillProvider directly — callers
 * must obtain a scoped provider via {@link #scoped(Set)} so only explicitly allowed
 * skill ids are visible to an Agent. This prevents an Agent from discovering skills
 * that were never attached to it.
 *
 * @author stephen
 */
public class MongoSkillProvider {
    private static final int MAX_LISTED_RESOURCES = 10;
    private static final String SKILL_FILE = "SKILL.md";

    private static SkillResource findResource(SkillDefinition definition, String path) {
        if (definition.resources == null) return null;
        return definition.resources.stream()
            .filter(resource -> resource.path.equals(path))
            .findFirst().orElse(null);
    }

    @Inject
    SkillStore skillStore;

    @Inject
    SkillService skillService;

    /**
     * Returns a SkillProvider that only lists skills whose id is in {@code allowedIds}.
     * The set is held by reference, so callers may mutate it to grow the allowed set;
     * call {@link ai.core.skill.SkillRegistry#invalidateCache()} afterwards to force a re-list.
     */
    public SkillProvider scoped(Set<String> allowedIds) {
        return new ScopedProvider(allowedIds);
    }

    private record ResourceOwner(SkillDefinition definition, String path) {
    }

    private final class ScopedProvider implements SkillProvider {
        private final Set<String> allowedIds;

        ScopedProvider(Set<String> allowedIds) {
            this.allowedIds = allowedIds;
        }

        @Override
        public List<SkillMetadata> listSkills() {
            if (allowedIds.isEmpty()) return List.of();
            var snapshot = List.copyOf(allowedIds);
            var definitions = skillStore.listByIds(snapshot);
            return definitions.stream().map(skillService::toMetadata).toList();
        }

        @Override
        public String readContent(SkillMetadata skill) {
            if (skill.getContent() != null) return skill.getContent();
            throw new SkillLoadException("skill content not available: " + skill.getQualifiedName());
        }

        @Override
        public String readResource(SkillMetadata skill, String resourcePath) {
            var definition = skillService.findByQualifiedName(skill.getQualifiedName());
            var owner = ownerOf(definition, resourcePath);
            if (owner == null) throw notFound(skill, definition, resourcePath);
            if (SKILL_FILE.equals(owner.path())) return owner.definition().content;
            var resource = findResource(owner.definition(), owner.path());
            if (resource == null) throw notFound(skill, definition, resourcePath);
            if (resource.storagePath != null) throw notText(skill, owner, resourcePath);
            return resource.content;
        }

        /**
         * A skill in a multi-root plugin may reference a file by its plugin-root path (for example
         * {@code ads/references/tiktok-audit.md} from a skill under {@code skills/}); the hub stores each
         * declared root as its own skill, so resolve the leading segment against a sibling skill of the
         * same repository before failing. A sibling's {@code SKILL.md} is not a resource, so it resolves
         * to that skill's stored content.
         */
        private ResourceOwner ownerOf(SkillDefinition definition, String resourcePath) {
            if (findResource(definition, resourcePath) != null) return new ResourceOwner(definition, resourcePath);
            int slash = resourcePath.indexOf('/');
            if (slash <= 0 || definition.namespace == null) return null;
            if (definition.repoConfig == null || definition.repoConfig.repoUrl == null) return null;
            var sibling = skillService.findByQualifiedNameOrNull(
                definition.namespace + "/" + resourcePath.substring(0, slash));
            if (sibling == null || sibling.id.equals(definition.id)) return null;
            if (sibling.repoConfig == null || !definition.repoConfig.repoUrl.equals(sibling.repoConfig.repoUrl)) return null;
            var relativePath = resourcePath.substring(slash + 1);
            if (SKILL_FILE.equals(relativePath) && sibling.content != null) return new ResourceOwner(sibling, relativePath);
            return findResource(sibling, relativePath) == null ? null : new ResourceOwner(sibling, relativePath);
        }

        private SkillLoadException notFound(SkillMetadata skill, SkillDefinition definition, String resourcePath) {
            var message = "resource not found: " + resourcePath + " in " + skill.getQualifiedName();
            if (definition.resources == null || definition.resources.isEmpty()) {
                return new SkillLoadException(message + " (this skill has no resources; only SKILL.md is available)");
            }
            var listed = definition.resources.stream().map(resource -> resource.path)
                .limit(MAX_LISTED_RESOURCES).collect(Collectors.joining(", "));
            var suffix = definition.resources.size() > MAX_LISTED_RESOURCES
                ? " (+" + (definition.resources.size() - MAX_LISTED_RESOURCES) + " more)" : "";
            return new SkillLoadException(message + " (available: " + listed + suffix + ")");
        }

        private SkillLoadException notText(SkillMetadata skill, ResourceOwner owner, String resourcePath) {
            return new SkillLoadException("resource is a binary or large file and cannot be read as text: " + resourcePath
                + " in " + skill.getQualifiedName()
                + " (read it from the sandbox at /skill/" + owner.definition().name + "/" + owner.path()
                + " after use_skill, or pull the skill with the CLI)");
        }

        @Override
        public int priority() {
            return 10;
        }
    }
}
