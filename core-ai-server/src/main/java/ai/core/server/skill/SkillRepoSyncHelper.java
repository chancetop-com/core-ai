package ai.core.server.skill;

import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.SkillRepoConfig;
import ai.core.server.quality.domain.QualityEnforcement;
import ai.core.server.quality.domain.QualityIssueStatus;
import ai.core.server.skill.quality.SkillQualityGateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.LinkedHashSet;

/**
 * Isolates deterministic quality-gate rejections during repository imports and scheduled syncs.
 * Records the rejected commit on the individual skill so the same commit is not retried while
 * preserving the last known good version and allowing sibling skills in the same repo to sync.
 *
 * @author stephen
 */
public final class SkillRepoSyncHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkillRepoSyncHelper.class);
    public static final String STATUS_OK = "OK";
    public static final String STATUS_REJECTED = "REJECTED";
    private static final int MAX_SUMMARY_LENGTH = 500;

    public static boolean isQualityRejection(Throwable error) {
        return error instanceof SkillQualityGateException gateError
            && SkillQualityGateException.REJECTED.equals(gateError.errorCode());
    }

    public static SkillRepoConfig initialRepoConfig(SkillRepoSource source) {
        var config = new SkillRepoConfig();
        config.repoUrl = source.url();
        config.branch = source.branch() == null || source.branch().isBlank() ? "main" : source.branch().trim();
        config.skillPath = source.skillPath();
        config.lastSyncedAt = ZonedDateTime.now();
        config.lastCommitHash = source.commitHash();
        config.lastSyncStatus = STATUS_OK;
        return config;
    }

    public static String resolveSyncedSkillPath(Path repoDir, String configured) {
        var declared = new PluginFormatDetector().declaredSkillPaths(repoDir);
        if (!declared.isEmpty()) return String.join(",", declared);
        if (configured != null && !configured.isBlank()) return configured;
        return detectSkillPath(repoDir);
    }

    public static String detectSkillPath(Path repoDir) {
        var detected = new PluginFormatDetector().detectSkillPaths(repoDir);
        return detected.isEmpty() ? null : String.join(",", detected);
    }

    public static SkillRepoConfig nextSyncedConfig(SkillRepoConfig existing, String commitHash) {
        var copy = new SkillRepoConfig();
        if (existing != null) {
            copy.repoUrl = existing.repoUrl;
            copy.branch = existing.branch;
            copy.skillPath = existing.skillPath;
            copy.lastCommitHash = existing.lastCommitHash;
        }
        if (commitHash != null) copy.lastCommitHash = commitHash;
        copy.lastSyncedAt = ZonedDateTime.now();
        copy.lastSyncStatus = STATUS_OK;
        copy.lastRejectedCommitHash = null;
        copy.lastSyncError = null;
        return copy;
    }

    public static void recordPermanentRejection(SkillDefinition existing, String commitHash,
                                                SkillQualityGateException error, SkillStore skillStore) {
        var rejectedBy = rejectedRules(error);
        if (existing != null && existing.repoConfig != null) {
            existing.repoConfig.lastSyncStatus = STATUS_REJECTED;
            existing.repoConfig.lastRejectedCommitHash = commitHash;
            existing.repoConfig.lastSyncError = summarize(error);
            skillStore.replace(existing);
        }
        var qualifiedName = existing != null ? existing.qualifiedName : "unknown";
        logRejection(qualifiedName, commitHash, rejectedBy);
    }

    public static void logNewSkillRejection(String qualifiedName, String commitHash, SkillQualityGateException error) {
        logRejection(qualifiedName, commitHash, rejectedRules(error));
    }

    private static void logRejection(String qualifiedName, String commitHash, String rejectedBy) {
        LOGGER.warn("repo skill sync rejected: skill={}, commit={}, rejected-by={}",
            qualifiedName, commitHash == null ? "unknown" : commitHash, rejectedBy);
    }

    public static String rejectedRules(SkillQualityGateException error) {
        var report = error.report();
        if (report == null || report.issues == null || report.issues.isEmpty()) {
            return error.errorCode();
        }
        var ruleIds = new LinkedHashSet<String>();
        for (var issue : report.issues) {
            if (issue.enforcement == QualityEnforcement.BLOCK && issue.status == QualityIssueStatus.FAIL
                && issue.ruleId != null) {
                ruleIds.add(issue.ruleId);
            }
        }
        if (ruleIds.isEmpty()) {
            for (var issue : report.issues) {
                if (issue.ruleId != null) ruleIds.add(issue.ruleId);
            }
        }
        return ruleIds.isEmpty() ? error.errorCode() : String.join(",", ruleIds);
    }

    public static String summarize(SkillQualityGateException error) {
        var rules = rejectedRules(error);
        var detail = error.getMessage() == null ? rules : rules + ": " + error.getMessage();
        return detail.length() > MAX_SUMMARY_LENGTH ? detail.substring(0, MAX_SUMMARY_LENGTH) + "..." : detail;
    }

    private SkillRepoSyncHelper() {
    }
}
