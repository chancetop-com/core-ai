package ai.core.cli.hub.agent;

import ai.core.api.server.agenthub.AgentHubLookupResponse;
import ai.core.api.server.agenthub.AgentHubSummary;
import ai.core.cli.hub.HubCliError;
import ai.core.cli.hub.HubExitCodes;
import ai.core.cli.http.RemoteApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The {@code <id | name>} contract of the agent subcommands: recognized id shapes bypass the lookup,
 * a name resolves only when it is unambiguous, and a value without a name match falls through to the
 * server, which resolves it as an id or reports the 404.
 *
 * @author stephen
 */
class AgentNameResolverTest {
    private final AgentHubClient client = mock(AgentHubClient.class);
    private final AgentNameResolver resolver = new AgentNameResolver(client);

    @Test
    void uuidIdIsPassedThroughWithoutLookup() {
        var id = "5a172258-fdd3-4357-8f3d-4fe2783a88df";
        assertEquals(id, resolver.resolve(id));
        verifyNoInteractions(client);
    }

    @Test
    void legacyObjectIdIsPassedThroughWithoutLookup() {
        var id = "0123456789abcdef01234567";
        assertEquals(id, resolver.resolve(id));
        verifyNoInteractions(client);
    }

    @Test
    void uniqueNameResolvesToItsId() {
        when(client.lookup("reviewer")).thenReturn(lookup(summary("agent-1", "reviewer", null)));

        assertEquals("agent-1", resolver.resolve("reviewer"));
    }

    @Test
    void ambiguousNameIsUsageErrorListingCandidates() {
        when(client.lookup("reviewer")).thenReturn(lookup(
                summary("agent-1", "reviewer", Boolean.TRUE),
                summary("agent-2", "reviewer", null)));

        var error = assertThrows(HubCliError.class, () -> resolver.resolve("reviewer"));

        assertEquals(HubExitCodes.USAGE, error.exitCode);
        assertTrue(error.getMessage().contains("agent-1"), error.getMessage());
        assertTrue(error.getMessage().contains("agent-2"), error.getMessage());
        assertTrue(error.getMessage().contains("(mine)"), error.getMessage());
    }

    @Test
    void valueWithoutNameMatchFallsThroughForServerResolution() {
        when(client.lookup("assistant:someone@example.com")).thenReturn(lookup());

        assertEquals("assistant:someone@example.com", resolver.resolve("assistant:someone@example.com"));
    }

    @Test
    void missingNameFallsThroughToTheServer404() {
        when(client.lookup("ghost")).thenReturn(lookup());

        assertEquals("ghost", resolver.resolve("ghost"));
    }

    @Test
    void blankReferenceIsUsageError() {
        var error = assertThrows(HubCliError.class, () -> resolver.resolve(" "));

        assertEquals(HubExitCodes.USAGE, error.exitCode);
    }

    @Test
    void serverErrorsAreNotSwallowed() {
        when(client.lookup("ghost")).thenThrow(new RemoteApiException(403, "access denied"));

        var error = assertThrows(RemoteApiException.class, () -> resolver.resolve("ghost"));

        assertEquals(403, error.statusCode);
    }

    private AgentHubLookupResponse lookup(AgentHubSummary... candidates) {
        var response = new AgentHubLookupResponse();
        response.candidates = List.of(candidates);
        return response;
    }

    private AgentHubSummary summary(String id, String name, Boolean ownerIsMe) {
        var summary = new AgentHubSummary();
        summary.id = id;
        summary.name = name;
        summary.ownerIsMe = ownerIsMe;
        return summary;
    }
}
