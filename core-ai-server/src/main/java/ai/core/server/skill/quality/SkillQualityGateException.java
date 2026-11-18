package ai.core.server.skill.quality;

import ai.core.server.web.TypedWebError;
import core.framework.api.http.HTTPStatus;
import core.framework.json.JSON;
import core.framework.log.ErrorCode;

/**
 * Typed skill publish rejection. The error body keeps the existing {errorCode, message} envelope and
 * adds {@code quality_report} in the same snake-case shape as the agent report, so clients can show
 * per-rule details instead of only the summary message.
 *
 * @author core-ai
 */
public final class SkillQualityGateException extends RuntimeException implements ErrorCode, TypedWebError {
    private static final long serialVersionUID = 1L;

    public static final String REJECTED = "SKILL_QUALITY_REJECTED";
    public static final String UNAVAILABLE = "SKILL_QUALITY_UNAVAILABLE";
    public static final String STALE = "SKILL_QUALITY_STALE";
    public static final String DIRECTORY_REQUIRED = "SKILL_DIRECTORY_REQUIRED";
    public static final String METADATA_MISMATCH = "SKILL_METADATA_MISMATCH";
    public static final String NAME_IMMUTABLE = "SKILL_NAME_IMMUTABLE";

    public static SkillQualityGateException rejected(SkillQualityReport report) {
        var failed = report.issues == null ? 0 : report.issues.stream()
            .filter(issue -> issue.status == ai.core.server.quality.domain.QualityIssueStatus.FAIL
                && issue.enforcement == ai.core.server.quality.domain.QualityEnforcement.BLOCK)
            .count();
        return new SkillQualityGateException(REJECTED, HTTPStatus.UNPROCESSABLE_ENTITY,
            "Skill not published: " + failed + " quality rule(s) failed. The existing skill is unchanged.", report);
    }

    public static SkillQualityGateException unavailable(SkillQualityReport report) {
        return new SkillQualityGateException(UNAVAILABLE, HTTPStatus.SERVICE_UNAVAILABLE,
            "Skill not published: required quality checks could not complete.", report);
    }

    public static SkillQualityGateException stale(String message) {
        return new SkillQualityGateException(STALE, HTTPStatus.CONFLICT, message, null);
    }

    public static SkillQualityGateException directoryRequired() {
        return new SkillQualityGateException(DIRECTORY_REQUIRED, HTTPStatus.BAD_REQUEST,
            "Upload is missing the skill_dir field; upgrade the CLI or client and retry.", null);
    }

    public static SkillQualityGateException metadataMismatch(String field) {
        return new SkillQualityGateException(METADATA_MISMATCH, HTTPStatus.BAD_REQUEST,
            "Request field " + field + " does not match the parsed SKILL.md value; refresh the editor and retry.",
            null);
    }

    public static SkillQualityGateException nameImmutable() {
        return new SkillQualityGateException(NAME_IMMUTABLE, HTTPStatus.BAD_REQUEST,
            "An update cannot change the skill name/directoryName; create a new skill and retire the old one.",
            null);
    }

    private final String errorCode;
    private final HTTPStatus status;
    private final transient SkillQualityReport report;

    private SkillQualityGateException(String errorCode, HTTPStatus status, String message,
                                      SkillQualityReport report) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
        this.report = report;
    }

    @Override
    public String errorCode() {
        return errorCode;
    }

    @Override
    public HTTPStatus status() {
        return status;
    }

    @Override
    public String extraJson() {
        if (report == null) return null;
        return "\"quality_report\":" + JSON.toJSON(SkillQualityReportMapper.toView(report));
    }

    public SkillQualityReport report() {
        return report;
    }
}
