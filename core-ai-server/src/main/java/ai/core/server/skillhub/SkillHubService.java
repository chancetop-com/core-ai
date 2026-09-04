package ai.core.server.skillhub;

import ai.core.api.server.skillhub.SkillHubDetail;
import ai.core.api.server.skillhub.SkillHubLookupResponse;
import ai.core.api.server.skillhub.SkillHubNamespaceMatch;
import ai.core.api.server.skillhub.SkillHubResourceRef;
import ai.core.api.server.skillhub.SkillHubResourceResponse;
import ai.core.api.server.skillhub.SkillHubSearchResponse;
import ai.core.api.server.skillhub.SkillHubSummary;
import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.SkillResource;
import ai.core.server.skill.SkillArchiveBuilder;
import ai.core.server.skillhub.SkillCatalogService.CatalogSkill;
import ai.core.server.skillhub.SkillCatalogService.SearchOutcome;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.ConflictException;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZonedDateTime;
import java.util.HexFormat;

/**
 * Orchestrates Skill Hub operations over the metadata catalog, with content and
 * archive bodies loaded on demand from Mongo. Read endpoints bump lightweight
 * per-skill counters (fire-and-forget, failures only warn).
 *
 * @author stephen
 */
public class SkillHubService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkillHubService.class);

    @Inject
    SkillCatalogService catalog;
    @Inject
    SkillHubAccessPolicy accessPolicy;
    @Inject
    MongoCollection<SkillDefinition> skillCollection;
    @Inject
    SkillArchiveBuilder archiveBuilder;

    public SkillHubSearchResponse search(String query, String namespace, String sourceType, Integer limit) {
        SearchOutcome outcome = catalog.search(query, namespace, sourceType, limit);
        var response = new SkillHubSearchResponse();
        response.namespaces = outcome.namespaces().stream().map(hit -> {
            var view = new SkillHubNamespaceMatch();
            view.namespace = hit.namespace();
            view.matchedCount = hit.matchedCount();
            view.score = hit.score();
            return view;
        }).toList();
        response.skills = outcome.skills().stream().map(hit -> toSummary(hit.skill(), hit.score())).toList();
        return response;
    }

    public SkillHubLookupResponse lookup(String name) {
        var candidates = catalog.lookupByName(name);
        var response = new SkillHubLookupResponse();
        if (candidates.isEmpty()) {
            throw new NotFoundException("skill not found: " + name);
        }
        if (candidates.size() > 1) {
            var listed = candidates.stream().map(CatalogSkill::qualifiedName).toList();
            throw new ConflictException("ambiguous skill name \"" + name + "\", candidates: " + String.join(", ", listed),
                    "AMBIGUOUS_NAME");
        }
        response.candidates = candidates.stream().map(skill -> toSummary(skill, 0)).toList();
        return response;
    }

    public SkillHubDetail show(String namespace, String name) {
        var definition = definition(namespace, name);
        recordShow(namespace, name);
        return toDetail(definition);
    }

    public SkillHubResourceResponse resource(String namespace, String name, String path) {
        var definition = definition(namespace, name);
        var resource = findResource(definition, path);
        if (resource == null) {
            throw new NotFoundException("skill resource not found: " + namespace + "/" + name + " path=" + path);
        }
        var response = new SkillHubResourceResponse();
        response.path = resource.path;
        String content = resource.content == null ? "" : resource.content;
        response.content = content;
        response.size = content.getBytes(StandardCharsets.UTF_8).length;
        response.sha256 = sha256(content);
        return response;
    }

    public ArchiveBundle archive(String namespace, String name) {
        var definition = definition(namespace, name);
        byte[] bytes = archiveBuilder.build(definition);
        recordPull(definition.id, namespace, name);
        return new ArchiveBundle(definition, bytes);
    }

    private SkillDefinition definition(String namespace, String name) {
        var entry = catalog.find(namespace, name);
        if (entry == null) throw new NotFoundException("skill not found: " + namespace + "/" + name);
        var definition = skillCollection.get(entry.id())
                .orElseThrow(() -> new NotFoundException("skill not found: " + namespace + "/" + name));
        if (!accessPolicy.canView(definition)) {
            throw new ForbiddenException("skill is not accessible: " + namespace + "/" + name);
        }
        return definition;
    }

    private SkillResource findResource(SkillDefinition definition, String path) {
        if (definition.resources == null || path == null) return null;
        for (var resource : definition.resources) {
            if (path.equals(resource.path)) return resource;
        }
        return null;
    }

    private SkillHubDetail toDetail(SkillDefinition def) {
        var view = new SkillHubDetail();
        view.id = def.id;
        view.qualifiedName = def.qualifiedName;
        view.namespace = def.namespace;
        view.name = def.name;
        view.description = def.description;
        view.sourceType = def.sourceType != null ? def.sourceType.name().toLowerCase(java.util.Locale.ROOT) : null;
        view.digest = def.digest;
        view.allowedTools = def.allowedTools;
        view.metadata = def.metadata;
        view.content = def.content;
        view.updatedAt = def.updatedAt;
        if (def.resources != null) {
            view.resources = def.resources.stream().map(resource -> {
                var ref = new SkillHubResourceRef();
                ref.path = resource.path;
                String content = resource.content == null ? "" : resource.content;
                ref.size = content.getBytes(StandardCharsets.UTF_8).length;
                ref.sha256 = sha256(content);
                return ref;
            }).toList();
        }
        return view;
    }

    private SkillHubSummary toSummary(CatalogSkill skill, int score) {
        var view = new SkillHubSummary();
        view.id = skill.id();
        view.qualifiedName = skill.qualifiedName();
        view.namespace = skill.namespace();
        view.name = skill.name();
        view.description = skill.description();
        view.sourceType = skill.sourceType();
        view.digest = skill.digest();
        view.resourceCount = skill.resourcePaths().size();
        view.score = score;
        view.updatedAt = skill.updatedAt();
        return view;
    }

    private void recordShow(String namespace, String name) {
        try {
            skillCollection.update(Filters.eq("qualified_name", namespace + "/" + name),
                    Updates.inc("stats.show_count", 1));
        } catch (RuntimeException e) {
            LOGGER.warn("failed to record skill show, qualifiedName={}/{}", namespace, name, e);
        }
    }

    private void recordPull(String id, String namespace, String name) {
        try {
            skillCollection.update(Filters.eq("_id", id), Updates.combine(
                    Updates.inc("stats.pull_count", 1),
                    Updates.set("stats.last_pulled_at", ZonedDateTime.now())));
        } catch (RuntimeException e) {
            LOGGER.warn("failed to record skill pull, qualifiedName={}/{}", namespace, name, e);
        }
    }

    private String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public record ArchiveBundle(SkillDefinition definition, byte[] bytes) {
    }
}
