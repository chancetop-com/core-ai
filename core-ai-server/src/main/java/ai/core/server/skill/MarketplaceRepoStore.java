package ai.core.server.skill;

import ai.core.server.domain.MarketplaceRepo;

import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public interface MarketplaceRepoStore {
    List<MarketplaceRepo> listAll();

    Optional<MarketplaceRepo> get(String id);

    void insert(MarketplaceRepo repo);

    void delete(String id);
}
