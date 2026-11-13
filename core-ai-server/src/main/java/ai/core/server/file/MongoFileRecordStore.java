package ai.core.server.file;

import ai.core.server.domain.FileRecord;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.Aggregate;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * @author stephen
 */
public class MongoFileRecordStore implements FileRecordStore {
    public static MongoFileRecordStore of(MongoCollection<FileRecord> collection) {
        var store = new MongoFileRecordStore();
        store.fileRecordCollection = collection;
        return store;
    }

    @Inject
    MongoCollection<FileRecord> fileRecordCollection;

    @Override
    public Optional<FileRecord> get(String id) {
        return fileRecordCollection.get(id);
    }

    @Override
    public List<FileRecord> find(FileRecordQuery request) {
        var query = new Query();
        query.filter = filter(request);
        var sort = request.ascending
            ? Sorts.orderBy(Sorts.ascending(request.sortField), Sorts.ascending("_id"))
            : Sorts.orderBy(Sorts.descending(request.sortField), Sorts.ascending("_id"));
        query.sort = sort;
        query.skip = request.offset;
        query.limit = request.limit == null || request.limit <= 0 ? null : request.limit;
        if (request.excludeData) query.projection = com.mongodb.client.model.Projections.exclude("data");
        return fileRecordCollection.find(query);
    }

    @Override
    public long count(FileRecordQuery request) {
        return fileRecordCollection.count(filter(request));
    }

    private Bson filter(FileRecordQuery request) {
        var filters = new ArrayList<Bson>();
        if (request.userId != null) filters.add(Filters.eq("user_id", request.userId));
        addIn(filters, "_id", request.ids);
        addIn(filters, "content_hash", request.contentHashes);
        addIn(filters, "file_name", request.fileNames);
        if (request.shareToken != null) {
            filters.add(Filters.eq("share_token", request.shareToken));
            filters.add(Filters.type("share_token", "string"));
        }
        if (Boolean.TRUE.equals(request.sharedOnly)) filters.add(Filters.type("share_token", "string"));
        if (request.fileNameKeyword != null && !request.fileNameKeyword.isBlank()) {
            filters.add(Filters.regex("file_name", Pattern.quote(request.fileNameKeyword), "i"));
        }
        if (request.contentType != null) filters.add(Filters.eq("content_type", request.contentType));
        if (request.createdAfter != null) filters.add(Filters.gt("created_at", request.createdAfter));
        if (request.legacyInlineOnly) {
            filters.add(Filters.type("data", "string"));
            filters.add(Filters.exists("storage_path", false));
        }
        return filters.isEmpty() ? Filters.empty() : Filters.and(filters);
    }

    private void addIn(List<Bson> filters, String field, List<String> values) {
        if (values == null) return;
        filters.add(values.isEmpty() ? Filters.eq("_id", "__none__") : Filters.in(field, values));
    }

    @Override
    public void insert(FileRecord record) {
        fileRecordCollection.insert(record);
    }

    @Override
    public boolean insertIfAbsent(FileRecord record) {
        try {
            fileRecordCollection.insert(record);
            return true;
        } catch (com.mongodb.MongoWriteException e) {
            if (e.getCode() == 11000) return false;
            throw e;
        }
    }

    @Override
    public void updateThumbStoragePath(String id, String path) {
        fileRecordCollection.update(Filters.eq("_id", id), Updates.set("thumb_storage_path", path));
    }

    @Override
    public void updateFileName(String id, String fileName) {
        fileRecordCollection.update(Filters.eq("_id", id), Updates.set("file_name", fileName));
    }

    @Override
    public void markStored(String id, String storagePath) {
        fileRecordCollection.update(Filters.eq("_id", id), Updates.combine(
            Updates.set("storage_path", storagePath),
            Updates.unset("data")));
    }

    @Override
    public boolean claimShareToken(String id, String userId, String token, ZonedDateTime sharedAt) {
        return fileRecordCollection.update(Filters.and(
                Filters.eq("_id", id),
                Filters.eq("user_id", userId),
                Filters.or(
                    Filters.exists("share_token", false),
                    Filters.eq("share_token", null),
                    Filters.eq("share_token", ""))),
            Updates.combine(
                Updates.set("share_token", token),
                Updates.set("shared_at", sharedAt))) > 0;
    }

    @Override
    public List<String> sharedUserIds() {
        var aggregate = new Aggregate<Document>();
        aggregate.resultClass = Document.class;
        aggregate.pipeline = List.of(
            Aggregates.match(Filters.type("share_token", "string")),
            Aggregates.group("$user_id"),
            Aggregates.sort(Sorts.ascending("_id")));
        return fileRecordCollection.aggregate(aggregate).stream()
            .map(document -> document.getString("_id"))
            .filter(id -> id != null)
            .toList();
    }

    @Override
    public void delete(String id) {
        fileRecordCollection.delete(id);
    }
}
