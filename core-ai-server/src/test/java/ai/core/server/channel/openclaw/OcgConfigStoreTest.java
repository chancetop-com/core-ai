package ai.core.server.channel.openclaw;

import core.framework.mongo.MongoCollection;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The callback/send secret is server-owned: minted by the store, never supplied by a client.
 *
 * @author stephen
 */
class OcgConfigStoreTest {
    @Test
    void mintsDistinctSecrets() {
        var store = new OcgConfigStore();
        Set<String> secrets = new HashSet<>(8);
        for (int i = 0; i < 8; i++) {
            secrets.add(store.newSecret());
        }
        assertEquals(8, secrets.size());
        for (var secret : secrets) {
            assertTrue(secret.matches("[0-9a-f]{64}"), secret);
        }
    }

    @Test
    void keepsTheSecretTheConfigAlreadyHas() {
        var store = new OcgConfigStore();
        var config = new OcgConfigView();
        config.callbackSecret = "existing-secret";

        assertEquals("existing-secret", store.ensureCallbackSecret(config));
    }

    @Test
    void mintsAndPersistsWhenTheConfigHasNone() {
        var store = new OcgConfigStore();
        MongoCollection<OcgConfigView> collection = mock();
        store.collection = collection;
        var config = new OcgConfigView();
        config.id = "ocg-1";
        when(collection.get("ocg-1")).thenReturn(Optional.empty());

        var secret = store.ensureCallbackSecret(config);

        assertEquals(secret, config.callbackSecret);
        assertNotEquals("", secret);
        verify(collection).insert(config);
    }
}
