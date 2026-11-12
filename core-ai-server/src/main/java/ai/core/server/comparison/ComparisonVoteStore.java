package ai.core.server.comparison;

import ai.core.server.comparison.domain.ComparisonVote;

import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public interface ComparisonVoteStore {
    Optional<ComparisonVote> find(String comparisonId, String sessionA, Long turn);

    boolean insertIfAbsent(ComparisonVote vote);

    void replace(ComparisonVote vote);

    List<ComparisonVote> findByComparison(String comparisonId);

    List<ComparisonVote> findBySessionA(String comparisonId, String sessionA);

    long countBySessionA(String comparisonId, String sessionA);
}
