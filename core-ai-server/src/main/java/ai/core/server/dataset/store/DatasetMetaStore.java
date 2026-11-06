package ai.core.server.dataset.store;

import ai.core.server.domain.Dataset;

import java.util.List;

/**
 * Dataset metadata (name, schema, owner) storage. The record store and the view manager read the schema
 * through this interface, so switching to PostgreSQL moves both metadata and records together.
 *
 * @author stephen
 */
public interface DatasetMetaStore {
    List<Dataset> list(String query, Integer offset, Integer limit);

    long count(String query);

    Dataset get(String id);

    void create(Dataset dataset);

    void update(Dataset dataset);

    // stores that materialize the schema (postgres views) keep metadata and view changes in one transaction
    default void createWithSchema(Dataset dataset) {
        create(dataset);
    }

    default void updateWithSchema(Dataset dataset) {
        update(dataset);
    }

    void delete(String id);
}
