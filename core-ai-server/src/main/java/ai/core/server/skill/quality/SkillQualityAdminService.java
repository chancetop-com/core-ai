package ai.core.server.skill.quality;

import ai.core.api.server.skill.SkillQualityRuleListResponse;
import ai.core.api.server.skill.SkillQualityRuleView;
import ai.core.server.user.UserStore;
import core.framework.inject.Inject;
import core.framework.web.exception.ForbiddenException;

/**
 * Read-only projection of the built-in skill rule catalog. The source of truth is code, so the only
 * guarantee this service makes is that the response matches the rules the gate actually runs.
 *
 * @author core-ai
 */
public class SkillQualityAdminService {
    @Inject
    UserStore userStore;

    public SkillQualityRuleListResponse rules(String userId) {
        requireAdmin(userId);
        var response = new SkillQualityRuleListResponse();
        response.policyRevision = SkillQualityGate.POLICY_REVISION;
        response.rules = SkillQualityGate.ruleCatalog().stream().map(this::toView).toList();
        return response;
    }

    private SkillQualityRuleView toView(SkillRuleMeta meta) {
        var view = new SkillQualityRuleView();
        view.key = meta.key();
        view.revision = meta.revision();
        view.name = meta.name();
        view.category = meta.category();
        view.enforcement = meta.enforcement() == null ? null : meta.enforcement().name();
        view.requirement = meta.requirement();
        return view;
    }

    private void requireAdmin(String userId) {
        var user = userStore.get(userId).orElse(null);
        if (user == null || !"admin".equals(user.role) || "api".equals(user.userType)) {
            throw new ForbiddenException("only a human platform admin can view skill quality rules");
        }
    }
}
