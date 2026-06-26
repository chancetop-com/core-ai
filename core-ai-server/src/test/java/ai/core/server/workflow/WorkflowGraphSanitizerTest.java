package ai.core.server.workflow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowGraphSanitizerTest {
    @Test
    void redactsSecretLikeNodeConfigFields() {
        String sanitized = WorkflowGraphSanitizer.sanitize("""
            {"nodes":[{"id":"http","type":"HTTP","config":{
              "url":"https://api.example.com",
              "headers":{"Authorization":"Bearer abc","X-Api-Key":"key-1","Private-Key":"private-1","Accept":"application/json"},
              "client_secret":"secret-1",
              "body":"{}"
            }}],"edges":[]}
            """);

        assertFalse(sanitized.contains("Bearer abc"));
        assertFalse(sanitized.contains("key-1"));
        assertFalse(sanitized.contains("private-1"));
        assertFalse(sanitized.contains("secret-1"));
        assertTrue(sanitized.contains("https://api.example.com"));
        assertTrue(sanitized.contains("application/json"));
    }
}
