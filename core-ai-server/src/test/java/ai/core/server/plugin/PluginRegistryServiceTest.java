package ai.core.server.plugin;

import ai.core.api.server.plugin.RegisterPluginRequest;
import ai.core.server.domain.PluginRegistryEntry;
import core.framework.web.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PluginRegistryServiceTest {

    @SuppressFBWarnings("VA_FORMAT_STRING_USES_NEWLINE")
    private static String manifest(String id, String version, String required) {
        return """
                {
                  "name": "Hello Plugin",
                  "version": "%s",
                  "description": "example",
                  "coreai": {
                    "schemaVersion": 1,
                    "id": "%s",
                    "components": [{"kind": "skills", "path": "skills"}],
                    "capabilities": {"required": [%s], "optional": []}
                  }
                }
                """.formatted(version, id, required);
    }

    private static RegisterPluginRequest request(String id, String sha) {
        var request = new RegisterPluginRequest();
        request.manifest = manifest(id, "1.0.0", "\"engine.skills\"");
        request.downloadUrl = "https://example.com/hello-plugin.zip";
        request.sha256 = sha;
        request.size = 1024L;
        request.vendor = "Example";
        return request;
    }

    private static PluginRegistryService service(InMemoryStore store) {
        var service = new PluginRegistryService();
        service.store = store;
        return service;
    }

    @Test
    void registrationDerivesIdentityAndCapabilitiesFromTheManifest() {
        var store = new InMemoryStore();
        var view = service(store).register("user-1", request("example.hello-plugin", "a".repeat(64)));

        assertEquals("example.hello-plugin", view.id);
        assertEquals("Hello Plugin", view.name);
        assertEquals("1.0.0", view.version);
        assertEquals("a".repeat(64), view.sha256);
        assertEquals(List.of("engine.skills"), view.requiredCapabilities);
        assertEquals(List.of("skills"), view.components);
        assertEquals("user-1", view.ownerId);
        assertEquals(1, store.entries.size());
    }

    @Test
    void invalidDigestUrlSizeAndCapabilitiesAreRejected() {
        var registry = service(new InMemoryStore());
        assertThrows(BadRequestException.class, () -> registry.register("u", request("example.hello-plugin", "nope")));

        var badUrl = request("example.hello-plugin", "b".repeat(64));
        badUrl.downloadUrl = "ftp://example.com/p.zip";
        assertThrows(BadRequestException.class, () -> registry.register("u", badUrl));

        var badSize = request("example.hello-plugin", "b".repeat(64));
        badSize.size = 0L;
        assertThrows(BadRequestException.class, () -> registry.register("u", badSize));

        var unknownCapability = request("example.hello-plugin", "b".repeat(64));
        unknownCapability.manifest = manifest("example.hello-plugin", "1.0.0", "\"browser.control\"");
        assertThrows(BadRequestException.class, () -> registry.register("u", unknownCapability));
    }

    @Test
    void registrationRequiresAValidCoreaiManifest() {
        var registry = service(new InMemoryStore());

        var noCoreai = request("example.hello-plugin", "b".repeat(64));
        noCoreai.manifest = "{\"name\":\"x\",\"version\":\"1.0.0\"}";
        assertThrows(BadRequestException.class, () -> registry.register("u", noCoreai));

        var badId = request("Bad Id", "b".repeat(64));
        badId.manifest = manifest("Bad Id", "1.0.0", "\"engine.skills\"");
        assertThrows(BadRequestException.class, () -> registry.register("u", badId));
    }

    @Test
    void anUpdatePreservesCreatedAtAndKeepsOneEntry() {
        var store = new InMemoryStore();
        var registry = service(store);
        var first = registry.register("u", request("example.hello-plugin", "a".repeat(64)));
        var created = store.entries.getFirst().createdAt;

        var second = request("example.hello-plugin", "b".repeat(64));
        second.manifest = manifest("example.hello-plugin", "1.1.0", "\"engine.skills\"");
        var updated = registry.register("u", second);

        assertEquals(1, store.entries.size());
        assertEquals("1.1.0", updated.version);
        assertEquals(created, store.entries.getFirst().createdAt);
        assertEquals(first.id, updated.id);
    }

    private static final class InMemoryStore implements PluginRegistryStore {
        private final List<PluginRegistryEntry> entries = new ArrayList<>();

        @Override
        public Optional<PluginRegistryEntry> get(String id) {
            return entries.stream().filter(entry -> entry.id.equals(id)).findFirst();
        }

        @Override
        public List<PluginRegistryEntry> search(String query, int offset, int limit) {
            return entries.stream().skip(offset).limit(limit).toList();
        }

        @Override
        public long count(String query) {
            return entries.size();
        }

        @Override
        public void upsert(PluginRegistryEntry entry) {
            entries.removeIf(existing -> existing.id.equals(entry.id));
            entries.add(entry);
        }

        @Override
        public void delete(String id) {
            entries.removeIf(existing -> existing.id.equals(id));
        }
    }
}
