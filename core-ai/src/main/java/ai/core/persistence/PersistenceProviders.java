package ai.core.persistence;

import java.util.HashMap;
import java.util.Map;

/**
 * @author stephen
 */
public class PersistenceProviders {
    Map<PersistenceProviderType, PersistenceProvider> persistenceProviders = new HashMap<>();
    PersistenceProviderType defaultPersistenceProviderType;

    public PersistenceProvider getDefaultPersistenceProvider() {
        return persistenceProviders.get(defaultPersistenceProviderType);
    }

    public PersistenceProvider getPersistenceProvider(PersistenceProviderType persistenceProviderType) {
        return persistenceProviders.get(persistenceProviderType);
    }

    public void addPersistenceProvider(PersistenceProviderType persistenceProviderType, PersistenceProvider persistenceProvider) {
        persistenceProviders.put(persistenceProviderType, persistenceProvider);
    }
}
