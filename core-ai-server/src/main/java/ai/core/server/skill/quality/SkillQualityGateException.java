package ai.core.server.skill.quality;

import ai.core.server.web.TypedWebError;
import ai.core.utils.JsonUtil;
import core.framework.api.http.HTTPStatus;
import core.framework.log.ErrorCode;

/**
 * Typed skill publish rejection. The error body keeps the existing {errorCode, message} envelope and adds
 * quality_report, so older clients still read a usable message.
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
            "Skill 未发布：" + failed + " 项质量规则未通过。现有 skill 未改变。", report);
    }

    public static SkillQualityGateException unavailable(SkillQualityReport report) {
        return new SkillQualityGateException(UNAVAILABLE, HTTPStatus.SERVICE_UNAVAILABLE,
            "Skill 未发布：必需的质量检查未能完成。", report);
    }

    public static SkillQualityGateException stale(String message) {
        return new SkillQualityGateException(STALE, HTTPStatus.CONFLICT, message, null);
    }

    public static SkillQualityGateException directoryRequired() {
        return new SkillQualityGateException(DIRECTORY_REQUIRED, HTTPStatus.BAD_REQUEST,
            "上传缺少 skill_dir 字段；请升级 CLI 或客户端后重试。", null);
    }

    public static SkillQualityGateException metadataMismatch(String field) {
        return new SkillQualityGateException(METADATA_MISMATCH, HTTPStatus.BAD_REQUEST,
            "请求字段 " + field + " 与 SKILL.md 解析值不一致；请刷新编辑器后重试。", null);
    }

    public static SkillQualityGateException nameImmutable() {
        return new SkillQualityGateException(NAME_IMMUTABLE, HTTPStatus.BAD_REQUEST,
            "更新不允许改变 skill name/directoryName；改名请新建 skill 并下线旧 skill。", null);
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
        return "\"quality_report\":" + JsonUtil.toJson(report);
    }

    public SkillQualityReport report() {
        return report;
    }
}
