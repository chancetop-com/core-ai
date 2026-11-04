package ai.core.server.dataset.store;

import ai.core.server.domain.Dataset;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The existing Mongo metadata path, kept for the transition period and rollback.
 *
 * @author stephen
 */
public class MongoDatasetMetaStore implements DatasetMetaStore {
    @Inject
    MongoCollection<Dataset> datasetCollection;

    @Override
    public List<Dataset> list(String query, Integer offset, Integer limit) {
        var dbQuery = new Query();
        dbQuery.filter = filter(query);
        dbQuery.sort = Sorts.descending("updated_at");
        if (offset != null || limit != null) {
            dbQuery.skip = Math.max(0, offset != null ? offset : 0);
            dbQuery.limit = Math.min(Math.max(limit != null ? limit : 20, 1), 100);
        }
        return datasetCollection.find(dbQuery);
    }

    @Override
    public long count(String query) {
        return datasetCollection.count(filter(query));
    }

    @Override
    public Dataset get(String id) {
        return datasetCollection.get(id).orElse(null);
    }

    @Override
    public void create(Dataset dataset) {
        datasetCollection.insert(dataset);
    }

    @Override
    public void update(Dataset dataset) {
        datasetCollection.replace(dataset);
    }

    @Override
    public void delete(String id) {
        datasetCollection.delete(id);
    }

    private Bson filter(String query) {
        if (query == null || query.isBlank()) return Filters.empty();
        var pattern = Pattern.quote(query.trim());
        var filters = new ArrayList<Bson>();
        filters.add(Filters.regex("name", pattern, "i"));
        filters.add(Filters.regex("description", pattern, "i"));
        filters.add(Filters.regex("user_id", pattern, "i"));
        return Filters.or(filters);
    }
}
