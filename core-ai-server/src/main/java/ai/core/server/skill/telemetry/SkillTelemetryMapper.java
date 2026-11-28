package ai.core.server.skill.telemetry;

import ai.core.api.server.skill.SkillPapercutCategoryView;
import ai.core.api.server.skill.SkillPapercutStatusView;
import ai.core.api.server.skill.SkillPapercutView;
import ai.core.api.server.skill.SkillRuntimeStatView;

/**
 * Explicit mapper between skill telemetry domain models and API view DTOs.
 *
 * @author stephen
 */
public final class SkillTelemetryMapper {
    public static SkillPapercutCategory toCategory(SkillPapercutCategoryView view) {
        return view == null ? null : SkillPapercutCategory.valueOf(view.name());
    }

    public static SkillPapercutCategoryView toCategoryView(SkillPapercutCategory category) {
        return category == null ? SkillPapercutCategoryView.OTHER : SkillPapercutCategoryView.valueOf(category.name());
    }

    public static SkillPapercutStatus toStatus(SkillPapercutStatusView view) {
        return view == null ? null : SkillPapercutStatus.valueOf(view.name());
    }

    public static SkillPapercutStatusView toStatusView(SkillPapercutStatus status) {
        return status == null ? SkillPapercutStatusView.OPEN : SkillPapercutStatusView.valueOf(status.name());
    }

    public static SkillRuntimeStatView toStatView(SkillRuntimeStatRecord record) {
        var view = new SkillRuntimeStatView();
        view.packageDigest = record.packageDigest();
        view.invokeCount = record.invokeCount();
        view.resourceReadCount = record.resourceReadCount();
        view.materializeFailCount = record.materializeFailCount();
        view.attributedScriptCalls = record.attributedScriptCalls();
        view.attributedScriptErrors = record.attributedScriptErrors();
        view.lastInvokedAt = record.lastInvokedAt();
        view.updatedAt = record.updatedAt();
        return view;
    }

    public static SkillPapercutView toPapercutView(SkillPapercutRecord record, boolean privileged) {
        var view = new SkillPapercutView();
        view.id = record.id();
        view.skillId = record.skillId();
        view.qualifiedName = record.qualifiedName();
        view.packageDigest = record.packageDigest();
        view.category = toCategoryView(record.category());
        view.status = toStatusView(record.status());
        view.summary = record.summary();
        view.latestObservedError = record.latestObservedError();
        view.suggestedFix = record.suggestedFix();
        view.occurrenceCount = record.occurrenceCount();
        if (privileged) {
            view.reporterUserId = record.reporterUserId();
            view.latestSessionId = record.latestSessionId();
        }
        view.resolvedBy = record.resolvedBy();
        view.resolvedAt = record.resolvedAt();
        view.createdAt = record.createdAt();
        view.lastSeenAt = record.lastSeenAt();
        return view;
    }

    private SkillTelemetryMapper() {
    }
}
