package ai.core.server.web.foryou;

import ai.core.server.domain.UserReport;

import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public interface UserReportStore {
    Optional<UserReport> get(String id);

    List<UserReport> list(String userId);

    List<UserReport> recent(String userId, int limit);

    long count(String userId);

    void insert(UserReport report);

    boolean insertIfAbsent(UserReport report);

    void replace(UserReport report);

    void delete(String id);
}
