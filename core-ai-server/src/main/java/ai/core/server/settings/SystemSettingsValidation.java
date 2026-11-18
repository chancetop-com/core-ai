package ai.core.server.settings;

import ai.core.api.server.settings.SystemSettingsRequest;
import core.framework.web.exception.BadRequestException;

import java.util.Base64;

/**
 * Integration-field validation kept out of the settings service so the main class stays within the
 * repository's file-length limit.
 *
 * @author core-ai
 */
final class SystemSettingsValidation {
    static void validateIntegrations(SystemSettingsRequest request) {
        if (request.azureBlobAccountKey != null && !request.azureBlobAccountKey.isBlank()) {
            try {
                Base64.getDecoder().decode(request.azureBlobAccountKey.trim());
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("azure blob account key must be valid base64", "INVALID_BASE64", e);
            }
        }
        if (request.githubAppPrivateKey != null && !request.githubAppPrivateKey.isBlank()
                && !request.githubAppPrivateKey.contains("BEGIN")) {
            throw new BadRequestException("github app private key must be a PEM key");
        }
        validateNumeric(request.githubAppId, "githubAppId");
        validateNumeric(request.githubAppInstallationId, "githubAppInstallationId");
        validateSkillRepoSyncInterval(request.skillRepoSyncIntervalMinutes);
        SandboxAttachmentLimit.validate(request.sandboxAttachmentMaxBytes);
        TraceArchiveSettings.validate(request);
    }

    private static void validateSkillRepoSyncInterval(Integer interval) {
        if (interval == null) return;
        if (interval < SystemSettingsService.MIN_SKILL_REPO_SYNC_INTERVAL_MINUTES
                || interval > SystemSettingsService.MAX_SKILL_REPO_SYNC_INTERVAL_MINUTES) {
            throw new BadRequestException("skill repo sync interval must be between "
                    + SystemSettingsService.MIN_SKILL_REPO_SYNC_INTERVAL_MINUTES + " and "
                    + SystemSettingsService.MAX_SKILL_REPO_SYNC_INTERVAL_MINUTES + " minutes");
        }
    }

    private static void validateNumeric(String value, String field) {
        if (value == null || value.isBlank()) return;
        try {
            Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException(field + " must be a number", "INVALID_NUMBER", e);
        }
    }

    private SystemSettingsValidation() {
    }
}
