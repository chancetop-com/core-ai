package ai.core.server.plugin;

import ai.core.server.domain.PluginRegistryEntry;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * @author stephen
 */
public class MongoPluginRegistryStore implements PluginRegistryStore {
    @Inject
    MongoCollection<PluginRegistryEntry> pluginCollection;

    @Override
    public Optional<PluginRegistryEntry> get(String id) {
        return pluginCollection.get(id);
    }

    @Override
    public List<PluginRegistryEntry> search(String queryText, int offset, int limit) {
        var query = new Query();
        query.filter = filter(queryText);
        query.sort = Sorts.descending("updated_at");
        query.skip = Math.max(0, offset);
        query.limit = Math.clamp(limit, 1, 100);
        return pluginCollection.find(query);
    }

    @Override
    public long count(String queryText) {
        return pluginCollection.count(filter(queryText));
    }

    @Override
    public void upsert(PluginRegistryEntry entry) {
        pluginCollection.replace(entry);
    }

    @Override
    public void delete(String id) {
        pluginCollection.delete(id);
    }

    private Bson filter(String queryText) {
        if (queryText == null || queryText.isBlank()) return new Document();
        var pattern = Pattern.compile(Pattern.quote(queryText.trim()), Pattern.CASE_INSENSITIVE);
        var filters = new ArrayList<Bson>();
        filters.add(Filters.regex("name", pattern));
        filters.add(Filters.regex("description", pattern));
        filters.add(Filters.regex("vendor", pattern));
        filters.add(Filters.regex("_id", pattern));
        return Filters.or(filters);
    }
}
