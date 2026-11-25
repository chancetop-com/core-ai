package ai.core.server.report.application;

import ai.core.server.report.domain.ReportConstants;
import ai.core.server.report.domain.ReportSpec;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Validates derived-measure references and keeps the evaluation order explicit: a derived measure may
 * only reference measures declared before it, which also rules out cycles without a graph traversal.
 *
 * @author core-ai
 */
final class ReportDerivedMeasureValidator {
    static void validate(ReportSpec.Query query, Set<String> ids, List<ReportSpecValidator.Issue> issues) {
        if (query.derivedMeasures == null) return;
        for (int i = 0; i < query.derivedMeasures.size(); i++) {
            var derived = query.derivedMeasures.get(i);
            var path = "/query/derived_measures/" + i;
            if (derived == null || derived.id == null || derived.id.isBlank()) {
                issues.add(new ReportSpecValidator.Issue("DERIVED_ID_REQUIRED", path + "/id", "unique id", "补充 id"));
                continue;
            }
            if (ids.contains(derived.id)) {
                issues.add(new ReportSpecValidator.Issue("DUPLICATE_MEASURE_ID", path + "/id", "unique", "id 不能重复"));
                continue;
            }
            var available = new HashSet<>(ids);
            ids.add(derived.id);
            validateOne(query, derived, available, path, issues);
        }
    }

    private static void validateOne(ReportSpec.Query query, ReportSpec.DerivedMeasure derived, Set<String> available,
                                    String path, List<ReportSpecValidator.Issue> issues) {
        var op = derived.op == null ? "" : derived.op.toUpperCase(Locale.ROOT);
        switch (op) {
            case ReportConstants.OP_RATIO -> validateRatio(derived, available, path, issues);
            case ReportConstants.OP_ADD -> validateAdd(derived, available, path, issues);
            case ReportConstants.OP_MOVING_AVG -> validateMovingAverage(query, derived, available, path, issues);
            default -> issues.add(new ReportSpecValidator.Issue("INVALID_DERIVED_OP", path + "/op",
                    "ADD|RATIO|MOVING_AVG", "选择支持的派生运算"));
        }
    }

    private static void validateRatio(ReportSpec.DerivedMeasure derived, Set<String> available, String path,
                                      List<ReportSpecValidator.Issue> issues) {
        if (derived.numerator == null || derived.denominator == null || derived.numerator.equals(derived.denominator)) {
            issues.add(new ReportSpecValidator.Issue("INVALID_RATIO", path, "two different measure ids", "指定分子/分母"));
        } else if (!available.contains(derived.numerator) || !available.contains(derived.denominator)) {
            issues.add(new ReportSpecValidator.Issue("RATIO_REFERENCES_UNKNOWN", path,
                    "previously declared measure ids", "分子/分母必须先声明"));
        }
    }

    private static void validateAdd(ReportSpec.DerivedMeasure derived, Set<String> available, String path,
                                    List<ReportSpecValidator.Issue> issues) {
        if (derived.inputs == null || derived.inputs.size() < 2) {
            issues.add(new ReportSpecValidator.Issue("INVALID_ADD", path, "at least two measure ids", "ADD 至少两个输入"));
            return;
        }
        var inputs = new HashSet<String>();
        for (var input : derived.inputs) {
            if (input == null || !available.contains(input)) {
                issues.add(new ReportSpecValidator.Issue("ADD_REFERENCES_UNKNOWN", path + "/inputs",
                        "previously declared measure ids", "ADD 输入必须先声明"));
                return;
            }
            if (!inputs.add(input)) {
                issues.add(new ReportSpecValidator.Issue("INVALID_ADD_INPUTS", path + "/inputs",
                        "unique measure ids", "ADD 输入不能重复"));
                return;
            }
        }
    }

    private static void validateMovingAverage(ReportSpec.Query query, ReportSpec.DerivedMeasure derived,
                                              Set<String> available, String path, List<ReportSpecValidator.Issue> issues) {
        if (derived.window == null) derived.window = 7;
        if (derived.input == null || !available.contains(derived.input)) {
            issues.add(new ReportSpecValidator.Issue("MOVING_AVG_INPUT_REQUIRED", path + "/input",
                    "previously declared measure id", "MOVING_AVG 需要输入 measure"));
        }
        if (derived.window < 2 || derived.window > ReportSpecValidator.MAX_QUERY_DAYS) {
            issues.add(new ReportSpecValidator.Issue("INVALID_MOVING_AVG_WINDOW", path + "/window",
                    "2.." + ReportSpecValidator.MAX_QUERY_DAYS, "调整窗口"));
        }
        if (query.bucket == null || ReportConstants.BUCKET_NONE.equals(query.bucket)) {
            issues.add(new ReportSpecValidator.Issue("MOVING_AVG_REQUIRES_BUCKET", path, "bucket != NONE", "MOVING_AVG 需要时间桶"));
        }
    }

    private ReportDerivedMeasureValidator() {
    }
}
