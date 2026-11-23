package ai.core.server.skill;

import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.SkillRepoConfig;
import ai.core.server.domain.SkillSourceType;
import ai.core.server.util.IdLists;
import ai.core.skill.SkillLoader;
import ai.core.skill.SkillMetadata;
import core.framework.inject.Inject;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author stephen
 */
public class SkillService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkillService.class);
    private static final int MAX_SKILL_FILE_SIZE = 10 * 1024 * 1024;
    // Skill bodies and resources stay inline in the skills table (UAT: 55 MB), so every summary
    // read detoasts the whole table (~1 s) just to drop those two keys. Summaries only change on
    // a write; a short per-pod TTL keeps list pages fast and bounds cross-pod staleness.
    private static final long SUMMARY_CACHE_TTL_MS = 10_000;

    private static ForbiddenException unavailableSkill() {
        return new ForbiddenException("skill is unavailable");
    }

    @Inject
    SkillStore skillStore;

    @Inject
    SkillBlobStore blobStore;

    @Inject
    SkillPublishService publishService;

    private final Map<String, SummaryCache> summaryCache = new ConcurrentHashMap<>();

    private volatile Runnable catalogInvalidator = () -> {
    };

    /** Hook for the skill hub catalog: fires after any write path that changed the catalog. */
    public void setCatalogInvalidator(Runnable invalidator) {
        if (invalidator != null) catalogInvalidator = invalidator;
    }

    private void invalidateCatalog() {
        summaryCache.clear();
        catalogInvalidator.run();
    }

    public String extractRepoOwner(String repoUrl) {
        return repoManager().extractRepoOwner(repoUrl);
    }

    public SkillDefinition upload(String userId, String namespace, String directoryName,
                                  byte[] skillFileBytes, Map<String, byte[]> resources) {
        return uploadOutcome(userId, namespace, directoryName, skillFileBytes, resources).entity();
    }

    public SkillPublishOutcome uploadOutcome(String userId, String namespace, String directoryName,
                                             byte[] skillFileBytes, Map<String, byte[]> resources) {
        if (directoryName == null || directoryName.isBlank()) {
            throw ai.core.server.skill.quality.SkillQualityGateException.directoryRequired();
        }
        var content = new String(skillFileBytes, StandardCharsets.UTF_8);
        var request = SkillPublishRequest.upload(userId, namespace, directoryName, content, resources);
        var parsedName = frontmatterName(content);
        if (parsedName != null) {
            request.existing = skillStore.findByQualifiedName(namespace + "/" + parsedName).orElse(null);
        }
        var outcome = publishService.publish(request);
        invalidateCatalog();
        LOGGER.info("uploaded skill, id={}, qualifiedName={}", outcome.entity().id, outcome.entity().qualifiedName);
        return outcome;
    }

    private String frontmatterName(String content) {
        try {
            return new ai.core.server.skill.quality.SkillDocumentParser().parse(content)
                .frontMatter.get("name") instanceof String name ? name : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public List<SkillDefinition> registerFromRepo(String userId, String repoUrl, String branch, String skillPath) {
        String namespace = repoManager().extractRepoOwner(repoUrl);
        if (namespace == null) {
            throw new RuntimeException("cannot extract owner from repo URL: " + repoUrl);
        }

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory(SkillRepoManager.TEMP_DIR_PREFIX);
            repoManager().cloneRepo(repoUrl, branch, tempDir);
            String commitHash = repoManager().headCommit(tempDir);

            String effectiveSkillPath = resolveSkillPath(tempDir, skillPath);
            if (effectiveSkillPath != null && !effectiveSkillPath.isBlank() && !effectiveSkillPath.equals(skillPath)) {
                LOGGER.info("resolved skill path(s): {} for repo {}", effectiveSkillPath, repoUrl);
            }

            List<SkillMetadata> skills;
            var loader = new SkillLoader(MAX_SKILL_FILE_SIZE);
            if (effectiveSkillPath != null && !effectiveSkillPath.isBlank()) {
                // skillPath may be comma-joined (auto-detected multiple locations), scan each separately
                skills = new ArrayList<>();
                for (String path : effectiveSkillPath.split(",")) {
                    Path scanDir = tempDir.resolve(path.trim());
                    skills.addAll(loader.loadFromSource(scanDir.toString()));
                }
            } else {
                skills = loader.loadFromSource(tempDir.toString());
            }

            var results = new ArrayList<SkillDefinition>();
            var source = new SkillRepoSource(repoUrl, branch, effectiveSkillPath, commitHash);
            for (var skill : skills) {
                results.add(publishRepoSKill(userId, namespace, skill, source));
            }
            LOGGER.info("registered {} skills from repo {}", results.size(), repoUrl);
            invalidateCatalog();
            return results;
        } catch (IOException e) {
            throw new RuntimeException("failed to clone repo: " + repoUrl, e);
        } finally {
            repoManager().deleteTempDir(tempDir);
        }
    }

    public List<SkillDefinition> list(SkillFilter filter, String userId, String query, String searchIn, Integer offset, Integer limit) {
        return list(filter, userId, new ListOptions(query, searchIn, offset, limit, true));
    }

    public List<SkillDefinition> list(SkillFilter filter, String userId, ListOptions options) {
        String namespace = filter == null ? null : filter.namespace();
        var sourceType = sourceType(filter);
        if (SkillQueryHelper.notInMemoryFilters(userId, options.query())) {
            return options.includeContent()
                ? skillStore.list(namespace, sourceType, options.offset(), options.limit())
                : SkillQueryHelper.page(summaryCandidates(namespace, sourceType), options.offset(), options.limit());
        }

        var searchScope = SkillQueryHelper.normalizedSearchIn(options.searchIn());
        var candidates = candidates(namespace, sourceType, options.includeContent());
        var filtered = candidates.stream()
            .filter(skill -> SkillQueryHelper.matchesUserId(skill, userId)
                && SkillQueryHelper.matchesQuery(skill, options.query(), searchScope))
            .toList();
        return SkillQueryHelper.page(filtered, options.offset(), options.limit());
    }

    public long count(SkillFilter filter, String userId, String query, String searchIn) {
        String namespace = filter == null ? null : filter.namespace();
        var sourceType = sourceType(filter);
        if (SkillQueryHelper.notInMemoryFilters(userId, query)) {
            return summaryCandidates(namespace, sourceType).size();
        }

        var searchScope = SkillQueryHelper.normalizedSearchIn(searchIn);
        // counting never reads the entities, so only a content search needs the bodies
        var candidates = SkillQueryHelper.requiresContent(searchScope)
            ? skillStore.list(namespace, sourceType, null, null)
            : summaryCandidates(namespace, sourceType);
        return candidates.stream()
            .filter(skill -> SkillQueryHelper.matchesUserId(skill, userId) && SkillQueryHelper.matchesQuery(skill, query, searchScope))
            .count();
    }

    private List<SkillDefinition> candidates(String namespace, SkillSourceType sourceType, boolean includeContent) {
        return includeContent
            ? skillStore.list(namespace, sourceType, null, null)
            : summaryCandidates(namespace, sourceType);
    }

    private List<SkillDefinition> summaryCandidates(String namespace, SkillSourceType sourceType) {
        var key = (namespace == null ? "" : namespace) + '\u0000' + (sourceType == null ? "" : sourceType.name());
        var now = System.currentTimeMillis();
        var cached = summaryCache.get(key);
        if (cached != null && now - cached.loadedAt() < SUMMARY_CACHE_TTL_MS) return cached.skills();
        var skills = List.copyOf(skillStore.listSummaries(namespace, sourceType, null, null));
        summaryCache.put(key, new SummaryCache(skills, now));
        return skills;
    }

    private SkillSourceType sourceType(SkillFilter filter) {
        if (filter == null || filter.sourceType() == null || filter.sourceType().isBlank()) return null;
        return SkillSourceType.valueOf(filter.sourceType());
    }

    public SkillDefinition get(String id) {
        return skillStore.get(id)
            .orElseThrow(() -> new NotFoundException("skill not found, id=" + id));
    }

    public SkillDefinition findByQualifiedName(String qualifiedName) {
        return skillStore.findByQualifiedName(qualifiedName)
            .orElseThrow(() -> new NotFoundException("skill not found: " + qualifiedName));
    }

    public SkillDefinition findOrNull(String id) {
        return id == null ? null : skillStore.get(id).orElse(null);
    }

    public SkillDefinition findByQualifiedNameOrNull(String qualifiedName) {
        return qualifiedName == null ? null
            : skillStore.findByQualifiedName(qualifiedName).orElse(null);
    }

    public List<SkillDefinition> findByName(String name) {
        return name == null ? List.of() : skillStore.findByName(name);
    }

    public void delete(String id) {
        var entity = skillStore.get(id).orElse(null);
        skillStore.delete(id);
        if (entity != null) blobStore.deleteReplaced(entity.resources, null);
        invalidateCatalog();
        LOGGER.info("deleted skill, id={}", id);
    }

    public SkillDefinition update(String id, String description, String content, List<String> allowedTools, List<SkillResourceUpdate> updates) {
        return updateOutcome(id, description, content, allowedTools, updates).entity();
    }

    public SkillPublishOutcome updateOutcome(String id, String description, String content,
                                             List<String> allowedTools, List<SkillResourceUpdate> updates) {
        var entity = get(id);
        var writer = writer();
        Map<String, byte[]> resources = updates != null
            ? writer.mergeBytes(entity.resources, updates)
            : writer.bytesOf(entity.resources);
        var request = new SkillPublishRequest();
        request.userId = entity.userId;
        request.namespace = entity.namespace;
        request.directoryName = entity.directoryName != null ? entity.directoryName : entity.name;
        request.content = content;
        request.resources = resources;
        request.existing = entity;
        request.sourceType = entity.sourceType;
        request.repoConfig = entity.repoConfig;
        request.mirroredDescription = description;
        request.mirroredAllowedTools = allowedTools;
        var outcome = publishService.publish(request);
        invalidateCatalog();
        return outcome;
    }

    long sweepStaleRepoTempDirs() {
        return repoManager().sweepStaleTempDirs();
    }

    public SkillDefinition syncFromRepo(String id) {
        return syncFromRepo(id, true);
    }

    private SkillDefinition syncFromRepo(String id, boolean force) {
        var entity = get(id);
        if (entity.sourceType != SkillSourceType.REPO || entity.repoConfig == null) {
            throw new RuntimeException("skill is not from a repo, id=" + id);
        }
        var config = entity.repoConfig;
        String remoteHead = force ? null : repoManager().remoteHead(config.repoUrl, config.branch);
        if (remoteHead != null && remoteHead.equals(config.lastCommitHash)) {
            LOGGER.info("skill repo unchanged, sync skipped, id={}, qualifiedName={}, commit={}", entity.id, entity.qualifiedName, remoteHead);
            return entity;
        }
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory(SkillRepoManager.SYNC_TEMP_DIR_PREFIX);
            repoManager().cloneRepo(config.repoUrl, config.branch, tempDir);
            String commitHash = remoteHead != null ? remoteHead : repoManager().headCommit(tempDir);

            String effectiveSkillPath = resolveSkillPath(tempDir, config.skillPath);

            List<SkillMetadata> skills;
            var loader = new SkillLoader(MAX_SKILL_FILE_SIZE);
            if (effectiveSkillPath != null && !effectiveSkillPath.isBlank()) {
                skills = new ArrayList<>();
                for (String path : effectiveSkillPath.split(",")) {
                    Path scanDir = tempDir.resolve(path.trim());
                    skills.addAll(loader.loadFromSource(scanDir.toString()));
                }
            } else {
                skills = loader.loadFromSource(tempDir.toString());
            }

            for (var skill : skills) {
                if (skill.getName().equals(entity.name)) {
                    syncMatchedSkill(entity, skill, commitHash);
                    LOGGER.info("synced skill from repo, id={}, qualifiedName={}, commit={}", entity.id, entity.qualifiedName, commitHash);
                    return entity;
                }
            }
            throw new RuntimeException("skill not found in repo after sync, name=" + entity.name);
        } catch (IOException e) {
            throw new RuntimeException("failed to sync repo: " + config.repoUrl, e);
        } finally {
            repoManager().deleteTempDir(tempDir);
        }
    }

    SkillDefinition syncFromRepoIfChanged(String id) {
        return syncFromRepo(id, false);
    }

    private SkillDefinition publishRepoSKill(String userId, String namespace, SkillMetadata skill, SkillRepoSource source) {
        var skillDir = skill.getSkillDir() != null ? Path.of(skill.getSkillDir()) : Path.of(skill.getPath()).getParent();
        var directoryName = skillDir == null ? null : skillDir.getFileName();
        if (directoryName == null) {
            throw new RuntimeException("cannot determine skill directory for " + skill.getName() + ", path=" + skill.getPath());
        }
        var request = SkillPublishRequest.upload(userId, namespace, directoryName.toString(),
            repoManager().readSkillMdFromDir(skillDir), repoManager().readResourceBytes(skillDir, skill.getResources()));
        request.existing = skillStore.findByQualifiedName(namespace + "/" + skill.getName()).orElse(null);
        request.sourceType = SkillSourceType.REPO;
        request.repoConfig = repoConfig(source);
        return publishService.publish(request).entity();
    }

    /**
     * A plugin manifest owns its skill layout: a path stored by an older detection can miss a declared
     * root (for example a shared reference root next to {@code skills/}), so a manifest declaration wins
     * over the stored value. Repos without a manifest keep their configured path, and only an
     * unconfigured repo falls back to convention detection.
     */
    private String resolveSkillPath(Path repoDir, String configured) {
        var detector = new PluginFormatDetector();
        var declared = detector.declaredSkillPaths(repoDir);
        if (!declared.isEmpty()) return String.join(",", declared);
        if (configured != null && !configured.isBlank()) return configured;
        var detected = detector.detectSkillPaths(repoDir);
        return detected.isEmpty() ? null : String.join(",", detected);
    }

    private SkillRepoConfig repoConfig(SkillRepoSource source) {
        var config = new SkillRepoConfig();
        config.repoUrl = source.url();
        config.branch = source.branch() == null || source.branch().isBlank() ? "main" : source.branch().trim();
        config.skillPath = source.skillPath();
        config.lastSyncedAt = ZonedDateTime.now();
        config.lastCommitHash = source.commitHash();
        return config;
    }

    private SkillDefinition syncMatchedSkill(SkillDefinition entity, SkillMetadata skill, String commitHash) {
        var skillDir = skill.getSkillDir() != null
            ? Path.of(skill.getSkillDir())
            : Path.of(skill.getPath()).getParent();
        if (skillDir == null) {
            throw new RuntimeException("cannot determine skill directory for " + skill.getName() + ", path=" + skill.getPath());
        }
        if (entity.repoConfig != null) {
            if (commitHash != null) entity.repoConfig.lastCommitHash = commitHash;
            entity.repoConfig.lastSyncedAt = ZonedDateTime.now();
        }
        var request = new SkillPublishRequest();
        request.userId = entity.userId;
        request.namespace = entity.namespace;
        request.directoryName = entity.directoryName != null ? entity.directoryName : entity.name;
        request.content = repoManager().readSkillMdFromDir(skillDir);
        request.resources = repoManager().readResourceBytes(skillDir, skill.getResources());
        request.existing = entity;
        request.sourceType = SkillSourceType.REPO;
        request.repoConfig = entity.repoConfig;
        var outcome = publishService.publish(request);
        invalidateCatalog();
        return outcome.entity();
    }

    public SkillDefinition download(String id) {
        return get(id);
    }

    SkillRepoManager repoManager() {
        return new SkillRepoManager(skillStore, blobStore);
    }

    private SkillResourceWriter writer() {
        return new SkillResourceWriter(blobStore);
    }

    public Map<String, String> batchResolve(Set<String> skillIds) {
        var cleanIds = IdLists.clean(new ArrayList<>(skillIds));
        if (cleanIds.isEmpty()) return Map.of();
        return skillStore.findNames(cleanIds);
    }

    public List<SkillMetadata> resolveSkills(List<String> skillIds) {
        var cleanSkillIds = IdLists.clean(skillIds);
        if (cleanSkillIds.isEmpty()) return List.of();
        var result = new ArrayList<SkillMetadata>();
        for (var id : cleanSkillIds) {
            skillStore.get(id).ifPresent(def -> result.add(toMetadata(def)));
        }
        return result;
    }

    // Skills are shared catalog entries. Keep callerUserId in the API because callers still use it for Agent/session
    // authorization, but Skill resolution itself only requires that every referenced Skill exists.
    public List<SkillMetadata> resolveAccessibleSkills(List<String> skillIds, String callerUserId) {
        var cleanSkillIds = IdLists.clean(skillIds);
        if (cleanSkillIds.isEmpty()) return List.of();
        var result = new ArrayList<SkillMetadata>(cleanSkillIds.size());
        for (var id : cleanSkillIds) {
            var definition = skillStore.get(id).orElseThrow(SkillService::unavailableSkill);
            result.add(toMetadata(definition));
        }
        return result;
    }

    public SkillMetadata toMetadata(SkillDefinition def) {
        var resourcePaths = def.resources != null
            ? def.resources.stream().map(r -> r.path).toList()
            : Collections.<String>emptyList();
        return SkillMetadata.builder(def.name, def.description != null ? def.description : "", null)
            .namespace(def.namespace)
            .content(def.content)
            .allowedTools(def.allowedTools != null ? def.allowedTools : Collections.emptyList())
            .metadata(def.metadata != null ? def.metadata : Collections.emptyMap())
            .resources(resourcePaths)
            .build();
    }

    /**
     * @param includeContent false only when the caller maps catalog metadata (the list view): skill bodies are then
     *                       never selected, so the returned entities carry no {@code content}/{@code resources}
     */
    public record ListOptions(String query, String searchIn, Integer offset, Integer limit, boolean includeContent) {
    }

    private record SummaryCache(List<SkillDefinition> skills, long loadedAt) {
    }
}
