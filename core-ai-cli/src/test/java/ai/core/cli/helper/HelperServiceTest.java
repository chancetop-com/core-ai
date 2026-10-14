package ai.core.cli.helper;

import ai.core.api.server.agenthub.AgentHubDetail;
import ai.core.cli.hub.agent.AgentHubClient;
import ai.core.cli.http.RemoteApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Validation rules and the hub summary snapshot of the helper management surface.
 *
 * @author stephen
 */
class HelperServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void addSnapshotsAgentSummaryFromHub() {
        var client = new FakeHubClient();
        client.detail.name = "HR 助手";
        client.detail.description = "公司人力资源政策问答";

        var entry = service(client).add("a1", "离职流程先问它");

        assertEquals("HR 助手", entry.name);
        assertEquals("公司人力资源政策问答", entry.description);
        assertEquals("离职流程先问它", entry.whenToUse);
        assertNotNull(entry.addedAt);
    }

    @Test
    void duplicateAddIsRejected() {
        var service = service(new FakeHubClient());
        service.add("a1", null);

        var error = assertThrows(HelperException.class, () -> service.add("a1", null));
        assertEquals("HELPER_EXISTS", error.code());
    }

    @Test
    void limitIsEnforced() {
        var service = service(new FakeHubClient());
        for (int i = 0; i < HelperConfigStore.MAX_HELPERS; i++) {
            service.add("a" + i, null);
        }

        var error = assertThrows(HelperException.class, () -> service.add("extra", null));
        assertEquals("HELPER_LIMIT", error.code());
    }

    @Test
    void addWithoutLoginIsRejected() {
        var service = new HelperService(new HelperConfigStore(tempDir.resolve("helpers.json")), () -> null);

        var error = assertThrows(HelperException.class, () -> service.add("a1", null));
        assertEquals("NOT_LOGGED_IN", error.code());
    }

    @Test
    void addOfInvisibleAgentMapsToAgentNotFound() {
        var client = new FakeHubClient();
        client.showError = new RemoteApiException(404, "agent not found");

        var error = assertThrows(HelperException.class, () -> service(client).add("a1", null));
        assertEquals("AGENT_NOT_FOUND", error.code());
    }

    @Test
    void updateAndRemoveUnknownAreRejected() {
        var service = service(new FakeHubClient());

        assertEquals("HELPER_NOT_FOUND",
                assertThrows(HelperException.class, () -> service.update("x", "y", null)).code());
        assertEquals("HELPER_NOT_FOUND",
                assertThrows(HelperException.class, () -> service.remove("x")).code());
    }

    private HelperService service(FakeHubClient client) {
        return new HelperService(new HelperConfigStore(tempDir.resolve("helpers.json")), () -> client);
    }

    private static final class FakeHubClient extends AgentHubClient {
        private final AgentHubDetail detail = new AgentHubDetail();
        private RemoteApiException showError;

        FakeHubClient() {
            super("https://server.example", "test-key", false);
        }

        @Override
        public AgentHubDetail show(String id) {
            if (showError != null) {
                throw showError;
            }
            return detail;
        }
    }
}
