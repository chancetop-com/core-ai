package ai.core.server.skill;

import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.SkillRepoConfig;
import ai.core.server.domain.SkillResource;
import ai.core.server.domain.SkillSourceType;
import ai.core.skill.SkillLoadException;
import ai.core.skill.SkillMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author stephen
 */
class MongoSkillProviderTest {
    private static final String REPO = "https://github.com/AgriciDaniel/claude-ads";

    private static SkillDefinition definition(String name, String repoUrl, List<SkillResource> resources) {
        var definition = new SkillDefinition();
        definition.id = name + "-id";
        definition.namespace = "AgriciDaniel";
        definition.name = name;
        definition.qualifiedName = "AgriciDaniel/" + name;
        definition.sourceType = SkillSourceType.REPO;
        definition.resources = resources;
        if (repoUrl != null) {
            definition.repoConfig = new SkillRepoConfig();
            definition.repoConfig.repoUrl = repoUrl;
        }
        return definition;
    }

    private static SkillResource text(String path, String content) {
        var resource = new SkillResource();
        resource.path = path;
        resource.content = content;
        return resource;
    }

    private static SkillMetadata metadata(String name) {
        return SkillMetadata.builder(name, "description", null).namespace("AgriciDaniel").build();
    }

    private SkillService skillService;
    private MongoSkillProvider provider;

    @BeforeEach
    void setUp() {
        skillService = mock(SkillService.class);
        provider = new MongoSkillProvider();
        provider.skillService = skillService;
    }

    @Test
    void readsOwnResource() {
        var resources = new ArrayList<SkillResource>();
        resources.add(text("references/tiktok-audit.md", "own"));
        when(skillService.findByQualifiedName("AgriciDaniel/ads-tiktok"))
            .thenReturn(definition("ads-tiktok", REPO, resources));

        assertEquals("own", provider.scoped(java.util.Set.of("ads-tiktok-id"))
            .readResource(metadata("ads-tiktok"), "references/tiktok-audit.md"));
    }

    @Test
    void resolvesPluginRootPathThroughSiblingSkillOfTheSameRepo() {
        when(skillService.findByQualifiedName("AgriciDaniel/ads-tiktok"))
            .thenReturn(definition("ads-tiktok", REPO, null));
        when(skillService.findByQualifiedNameOrNull("AgriciDaniel/ads"))
            .thenReturn(definition("ads", REPO, List.of(text("references/tiktok-audit.md", "shared"))));

        assertEquals("shared", provider.scoped(java.util.Set.of("ads-tiktok-id"))
            .readResource(metadata("ads-tiktok"), "ads/references/tiktok-audit.md"));
    }

    @Test
    void resolvesSiblingSkillMdToItsContent() {
        var sibling = definition("ads", REPO, null);
        sibling.content = "# Ads conductor";
        when(skillService.findByQualifiedName("AgriciDaniel/ads-tiktok"))
            .thenReturn(definition("ads-tiktok", REPO, null));
        when(skillService.findByQualifiedNameOrNull("AgriciDaniel/ads")).thenReturn(sibling);

        assertEquals("# Ads conductor", provider.scoped(java.util.Set.of("ads-tiktok-id"))
            .readResource(metadata("ads-tiktok"), "ads/SKILL.md"));
    }

    @Test
    void doesNotResolveThroughSiblingSkillOfAnotherRepo() {
        when(skillService.findByQualifiedName("AgriciDaniel/ads-tiktok"))
            .thenReturn(definition("ads-tiktok", REPO, null));
        when(skillService.findByQualifiedNameOrNull("AgriciDaniel/ads"))
            .thenReturn(definition("ads", "https://github.com/AgriciDaniel/other", List.of(text("references/tiktok-audit.md", "shared"))));

        var error = assertThrows(SkillLoadException.class, () -> provider.scoped(java.util.Set.of("ads-tiktok-id"))
            .readResource(metadata("ads-tiktok"), "ads/references/tiktok-audit.md"));
        assertTrue(error.getMessage().contains("this skill has no resources"));
    }

    @Test
    void listsAvailableResourcesWhenPathIsMissing() {
        var resources = new ArrayList<SkillResource>();
        resources.add(text("references/other.md", "x"));
        when(skillService.findByQualifiedName("AgriciDaniel/ads-tiktok"))
            .thenReturn(definition("ads-tiktok", REPO, resources));

        var error = assertThrows(SkillLoadException.class, () -> provider.scoped(java.util.Set.of("ads-tiktok-id"))
            .readResource(metadata("ads-tiktok"), "references/tiktok-audit.md"));
        assertTrue(error.getMessage().contains("available: references/other.md"));
    }
}
