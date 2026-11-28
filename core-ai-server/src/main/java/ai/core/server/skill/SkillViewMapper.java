package ai.core.server.skill;

import ai.core.api.server.skill.SkillDefinitionView;
import ai.core.api.server.skill.SkillSdkCapabilityView;
import ai.core.server.domain.SkillDefinition;
import ai.core.skill.SkillSdkCapabilityManifest;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared projection from SkillDefinition entity to SkillDefinitionView DTO.
 *
 * @author stephen
 */
public final class SkillViewMapper {
    public static SkillDefinitionView toView(SkillDefinition entity) {
        var view = new SkillDefinitionView();
        view.id = entity.id;
        view.namespace = entity.namespace;
        view.name = entity.name;
        view.qualifiedName = entity.qualifiedName;
        view.description = entity.description;
        view.sourceType = entity.sourceType.name();
        view.tier = SkillTiers.toView(SkillTiers.resolve(entity));
        view.allowedTools = entity.allowedTools;
        var manifest = SkillSdkCapabilityManifest.parse(entity.allowedTools, entity.metadata);
        view.sdkCapabilitiesVersion = manifest.enabled() ? manifest.contractVersion() : null;
        view.sdkCapabilities = sdkCapabilities(manifest);
        view.metadata = entity.metadata;
        view.version = entity.version;
        view.digest = entity.digest;
        if (entity.repoConfig != null) {
            view.lastSyncStatus = entity.repoConfig.lastSyncStatus;
            view.lastRejectedCommitHash = entity.repoConfig.lastRejectedCommitHash;
            view.lastSyncError = entity.repoConfig.lastSyncError;
        }
        view.userId = entity.userId;
        view.createdAt = entity.createdAt;
        view.updatedAt = entity.updatedAt;
        return view;
    }

    private static List<SkillSdkCapabilityView> sdkCapabilities(SkillSdkCapabilityManifest manifest) {
        if (!manifest.enabled()) return List.of();
        var views = new ArrayList<SkillSdkCapabilityView>(
            manifest.entries().size() + manifest.unsupported().size());
        for (var entry : manifest.entries()) {
            views.add(sdkCapability(entry));
        }
        for (var entry : manifest.unsupported()) {
            views.add(sdkCapability(entry));
        }
        return views;
    }

    private static SkillSdkCapabilityView sdkCapability(SkillSdkCapabilityManifest.Entry entry) {
        var view = new SkillSdkCapabilityView();
        view.kind = entry.kind();
        view.path = entry.displayPath();
        view.raw = entry.raw();
        view.supported = entry.supported();
        return view;
    }

    private SkillViewMapper() {
    }
}
