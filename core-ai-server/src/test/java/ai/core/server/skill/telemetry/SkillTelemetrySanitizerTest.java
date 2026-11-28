package ai.core.server.skill.telemetry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for credential, token, base64, and home-path masking in SkillTelemetrySanitizer.
 *
 * @author stephen
 */
class SkillTelemetrySanitizerTest {
    @Test
    void masksApiKeysBearerTokensDatabasePasswordsAndLongBase64() {
        String raw = "Request failed with Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload12345.sig67890"
                + " using sk-proj-abc123456789xyz and api_key=my_secret_key_999"
                + " db=postgres://admin:SuperSecretPass@db.internal:5432/core"
                + " blob=QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVowMTIzNDU2Nzg5+/==";

        String sanitized = SkillTelemetrySanitizer.sanitizeObservedError(raw);

        assertFalse(sanitized.contains("sk-proj-abc123456789xyz"));
        assertFalse(sanitized.contains("eyJhbGciOiJIUzI1NiJ9"));
        assertFalse(sanitized.contains("my_secret_key_999"));
        assertFalse(sanitized.contains("SuperSecretPass"));
        assertFalse(sanitized.contains("QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVowMTIzNDU2Nzg5"));
        assertTrue(sanitized.contains("Authorization: Bearer ***"));
        assertTrue(sanitized.contains("api_key=***"));
        assertTrue(sanitized.contains("postgres://admin:***@db.internal:5432/core"));
    }

    @Test
    void normalizesUnixAndWindowsHomeDirectories() {
        String unix = SkillTelemetrySanitizer.sanitizeObservedError("Missing file /Users/alice/.core-ai/skills/demo/run.py");
        String linux = SkillTelemetrySanitizer.sanitizeObservedError("Missing file /home/bob/work/script.sh");
        String windows = SkillTelemetrySanitizer.sanitizeObservedError("Missing file C:\\Users\\charlie\\skills\\run.ps1");

        assertEquals("Missing file ~/.core-ai/skills/demo/run.py", unix);
        assertEquals("Missing file ~/work/script.sh", linux);
        assertEquals("Missing file ~\\skills\\run.ps1", windows);
    }

    @Test
    void truncatesToConfiguredMaxLengthAndReturnsNullForBlank() {
        assertNull(SkillTelemetrySanitizer.sanitizeObservedError(null));
        assertNull(SkillTelemetrySanitizer.sanitizeObservedError("   "));

        String longText = "error detail ".repeat(100);
        assertEquals(SkillTelemetrySanitizer.MAX_OBSERVED_ERROR_LENGTH,
                SkillTelemetrySanitizer.sanitizeObservedError(longText).length());
        assertEquals(SkillTelemetrySanitizer.MAX_SUGGESTED_FIX_LENGTH,
                SkillTelemetrySanitizer.sanitizeSuggestedFix(longText).length());
    }
}
