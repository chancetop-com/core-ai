package ai.core.server.costalert;

import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public interface CostAlertRuleStore {
    Optional<CostAlertRule> get(String id);

    List<CostAlertRule> list();

    void insert(CostAlertRule rule);

    boolean insertIfAbsent(CostAlertRule rule);

    void replace(CostAlertRule rule);

    void delete(String id);
}

