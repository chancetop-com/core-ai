package ai.core.server.quality;

import ai.core.api.server.agent.AgentQualityReportView;
import ai.core.server.web.TypedWebError;
import core.framework.api.http.HTTPStatus;
import core.framework.json.JSON;
import core.framework.log.ErrorCode;

/**
 * Typed publish rejection. The body keeps the existing {errorCode, message} envelope and adds
 * {@code quality_report}; clients that only read {@code message} keep working.
 *
 * @author core-ai
 */
public final class QualityGateException extends RuntimeException implements ErrorCode, TypedWebError {
    private static final long serialVersionUID = 1L;

    public static final String REJECTED = "AGENT_QUALITY_REJECTED";
    public static final String INPUT_UNRESOLVED = "AGENT_QUALITY_INPUT_UNRESOLVED";
    public static final String INPUT_TOO_LARGE = "AGENT_QUALITY_INPUT_TOO_LARGE";
    public static final String UNAVAILABLE = "AGENT_QUALITY_UNAVAILABLE";
    public static final String STALE = "AGENT_QUALITY_STALE";
    public static final String IN_PROGRESS = "AGENT_PUBLISH_IN_PROGRESS";
    public static final String IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";
    public static final String CAPACITY = "AGENT_QUALITY_CAPACITY_EXCEEDED";

    public static QualityGateException rejected(AgentQualityReportView report) {
        return new QualityGateException(REJECTED, HTTPStatus.UNPROCESSABLE_ENTITY,
            "Agent 未发布：" + report.blockFailCount + " 项质量规则未通过。未创建新的发布版本。", report, null);
    }

    public static QualityGateException unavailable(AgentQualityReportView report) {
        return new QualityGateException(UNAVAILABLE, HTTPStatus.SERVICE_UNAVAILABLE,
            "Agent 未发布：必需的质量检查未能完成。", report, null);
    }

    public static QualityGateException inputUnresolved(AgentQualityReportView report) {
        return new QualityGateException(INPUT_UNRESOLVED, HTTPStatus.UNPROCESSABLE_ENTITY,
            "Agent 未发布：部分输入无法明确判定。", report, null);
    }

    public static QualityGateException inputTooLarge(AgentQualityReportView report) {
        return new QualityGateException(INPUT_TOO_LARGE, HTTPStatus.UNPROCESSABLE_ENTITY,
            "Agent 未发布：提示词超出质量检查预算。", report, null);
    }

    public static QualityGateException stale(String message) {
        return new QualityGateException(STALE, HTTPStatus.CONFLICT,
            message == null ? "Agent 在检查后被修改，请刷新后重试。" : message, null, null);
    }

    public static QualityGateException inProgress(String runId) {
        return new QualityGateException(IN_PROGRESS, HTTPStatus.CONFLICT,
            "同一 Agent 的发布检查正在处理中，请通过 runId 查询结果。runId=" + runId, null, null);
    }

    public static QualityGateException idempotencyConflict(String runId) {
        return new QualityGateException(IDEMPOTENCY_CONFLICT, HTTPStatus.CONFLICT,
            "Idempotency-Key 对应的候选内容不同，请使用新 key 重新提交。runId=" + runId, null, null);
    }

    public static QualityGateException capacity(int retryAfterSeconds) {
        return new QualityGateException(CAPACITY, HTTPStatus.TOO_MANY_REQUESTS,
            "质量检查并发已满，请稍后重试。", null, retryAfterSeconds);
    }

    public static QualityGateException metadataRejected(AgentQualityReportView report) {
        return new QualityGateException("AGENT_QUALITY_METADATA_REJECTED", HTTPStatus.UNPROCESSABLE_ENTITY,
            "已发布 Agent 的 name/description 修改未通过质量检查。", report, null);
    }

    private final String errorCode;
    private final HTTPStatus status;
    private final transient AgentQualityReportView report;
    private final Integer retryAfterSeconds;

    private QualityGateException(String errorCode, HTTPStatus status, String message,
                                 AgentQualityReportView report, Integer retryAfterSeconds) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
        this.report = report;
        this.retryAfterSeconds = retryAfterSeconds;
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
    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }

    @Override
    public String extraJson() {
        if (report == null) return null;
        return "\"quality_report\":" + JSON.toJSON(report);
    }

    public AgentQualityReportView report() {
        return report;
    }
}
