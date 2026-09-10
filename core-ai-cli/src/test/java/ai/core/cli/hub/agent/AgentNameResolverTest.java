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
import static org.mockito.Mockito.when;

/**
 * The {@code <id | name>} contract of the agent subcommands: ids bypass the lookup, a name resolves
 * only when it is unambiguous, and a missing name is a not-found error rather than a usage error.
 *
 * @author stephen
 */
class AgentNameResolverTest {
    private final AgentHubClient client = mock(AgentHubClient.class);
    private final AgentNameResolver resolver = new AgentNameResolver(client);

    @Test
    void idIsPassedThroughWithoutLookup() {
        var id = "0123456789abcdef01234567";
        assertEquals(id, resolver.resolve(id));
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
    void missingNameIsNotFound() {
        when(client.lookup("ghost")).thenReturn(lookup());

        var error = assertThrows(HubCliError.class, () -> resolver.resolve("ghost"));

        assertEquals(HubExitCodes.NOT_FOUND, error.exitCode);
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
