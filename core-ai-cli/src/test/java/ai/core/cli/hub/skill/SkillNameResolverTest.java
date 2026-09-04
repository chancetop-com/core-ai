package ai.core.cli.hub.skill;

import ai.core.api.server.skillhub.SkillHubLookupResponse;
import ai.core.api.server.skillhub.SkillHubSummary;
import ai.core.cli.hub.HubCliError;
import ai.core.cli.hub.HubExitCodes;
import ai.core.cli.http.RemoteApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillNameResolverTest {
    private final SkillHubClient client = mock(SkillHubClient.class);
    private final SkillNameResolver resolver = new SkillNameResolver();

    @Test
    void qualifiedNameIsUsedAsIs() {
        var resolved = resolver.resolve(client, "stephen/code-review");
        assertEquals("stephen", resolved.namespace());
        assertEquals("code-review", resolved.name());
    }

    @Test
    void malformedQualifiedNameIsUsageError() {
        var error = assertThrows(HubCliError.class, () -> resolver.resolve(client, "stephen/"));
        assertEquals(HubExitCodes.USAGE, error.exitCode);
    }

    @Test
    void uniqueBareNameResolvesThroughLookup() {
        var response = new SkillHubLookupResponse();
        var candidate = new SkillHubSummary();
        candidate.namespace = "stephen";
        candidate.name = "code-review";
        response.candidates = java.util.List.of(candidate);
        when(client.lookup("code-review")).thenReturn(response);

        var resolved = resolver.resolve(client, "code-review");
        assertEquals("stephen/code-review", resolved.qualifiedName());
    }

    @Test
    void missingBareNameIsNotFound() {
        when(client.lookup("missing")).thenThrow(new RemoteApiException(404, "skill not found: missing"));
        var error = assertThrows(HubCliError.class, () -> resolver.resolve(client, "missing"));
        assertEquals(HubExitCodes.NOT_FOUND, error.exitCode);
    }

    @Test
    void ambiguousBareNameIsUsageErrorWithCandidates() {
        when(client.lookup("code-review")).thenThrow(new RemoteApiException(409,
                "ambiguous skill name \"code-review\", candidates: stephen/code-review, anthropics/code-review"));
        var error = assertThrows(HubCliError.class, () -> resolver.resolve(client, "code-review"));
        assertEquals(HubExitCodes.USAGE, error.exitCode);
        assertTrue(error.getMessage().contains("anthropics/code-review"));
    }

    @Test
    void otherServerErrorsPropagate() {
        when(client.lookup("code-review")).thenThrow(new RemoteApiException(403, "access denied"));
        var error = assertThrows(RemoteApiException.class, () -> resolver.resolve(client, "code-review"));
        assertEquals(403, error.statusCode);
    }
}
