package ai.core.server.skill;

import ai.core.api.server.skill.SkillSdkCapabilityView;
import ai.core.api.server.skill.SkillSdkPlanResponse;
import ai.core.server.util.IdLists;
import ai.core.skill.SkillSdkCapabilityManifest;
import core.framework.inject.Inject;
import core.framework.web.exception.NotFoundException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure preview of the SDK capabilities a set of skills would contribute. It never resolves providers
 * or starts anything; the returned digest is the set the user is about to authorize.
 *
 * @author core-ai
 */
public class SkillSdkAssignmentPlanService {
    @Inject
    SkillService skillService;

    public SkillSdkPlanResponse plan(List<String> skillIds) {
        var ids = IdLists.clean(skillIds);
        var skills = new ArrayList<SkillSdkPlanResponse.SkillPlan>(ids.size());
        var canonical = new ArrayList<String>(ids.size());
        for (var id : ids) {
            var definition = skillService.findOrNull(id);
            if (definition == null) throw new NotFoundException("skill not found, id=" + id);
            var manifest = SkillSdkCapabilityManifest.parse(definition.allowedTools, definition.metadata);
            var plan = new SkillSdkPlanResponse.SkillPlan();
            plan.skillId = definition.id;
            plan.qualifiedName = definition.qualifiedName;
            plan.contentDigest = definition.digest;
            plan.sdkCapabilitiesVersion = manifest.enabled() ? manifest.contractVersion() : null;
            plan.capabilities = capabilities(manifest);
            skills.add(plan);
            canonical.add(definition.id + ":" + nullToEmpty(definition.digest) + ":"
                    + (manifest.enabled() ? nullToEmpty(manifest.digest()) : "disabled"));
        }
        canonical.sort(String::compareTo);
        var response = new SkillSdkPlanResponse();
        response.skills = skills;
        response.planDigest = sha256(String.join("\n", canonical));
        return response;
    }

    private List<SkillSdkCapabilityView> capabilities(SkillSdkCapabilityManifest manifest) {
        if (!manifest.enabled()) return List.of();
        var result = new ArrayList<SkillSdkCapabilityView>(
                manifest.entries().size() + manifest.unsupported().size());
        for (var entry : manifest.entries()) {
            result.add(capability(entry));
        }
        for (var entry : manifest.unsupported()) {
            result.add(capability(entry));
        }
        return result;
    }

    private SkillSdkCapabilityView capability(SkillSdkCapabilityManifest.Entry entry) {
        var view = new SkillSdkCapabilityView();
        view.kind = entry.kind();
        view.path = entry.displayPath();
        view.raw = entry.raw();
        view.supported = entry.supported();
        return view;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder(bytes.length * 2);
            for (var b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
