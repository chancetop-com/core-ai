package ai.core.server.artifact;

import ai.core.server.domain.ChatSession;
import ai.core.server.domain.FileRecord;
import ai.core.server.domain.User;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.Aggregate;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.Document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public class ArtifactService {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    // listing only exposes metadata; the base64 file content in `data` is never part of a list payload
    private static final org.bson.conversions.Bson FILE_LIST_PROJECTION = Projections.exclude("data", "storage_path");
    private static final org.bson.conversions.Bson SESSION_LOOKUP_PROJECTION = Projections.include("_id", "title", "artifacts");
    private static final org.bson.conversions.Bson SESSION_AGENT_LOOKUP_PROJECTION = Projections.include("artifacts");

    @Inject
    MongoCollection<FileRecord> fileRecordCollection;

    @Inject
    MongoCollection<ChatSession> chatSessionCollection;

    @Inject
    MongoCollection<User> userCollection;

    public MyArtifactResult listMy(String userId, Integer offset, Integer limit, String agentId) {
        int skip = offset != null && offset >= 0 ? offset : 0;
        int take = limit != null && limit > 0 ? Math.min(limit, MAX_LIMIT) : DEFAULT_LIMIT;

        var filters = new ArrayList<org.bson.conversions.Bson>();
        filters.add(Filters.eq("user_id", userId));
        if (agentId != null && !agentId.isBlank()) {
            var fileIdsByAgent = findFileIdsByAgent(userId, agentId);
            if (fileIdsByAgent.isEmpty()) {
                var empty = new MyArtifactResult();
                empty.total = 0;
                empty.artifacts = List.of();
                return empty;
            }
            filters.add(Filters.in("_id", fileIdsByAgent.toArray(new String[0])));
        }
        var filter = Filters.and(filters);

        var count = fileRecordCollection.count(filter);

        var query = new Query();
        query.filter = filter;
        query.sort = Sorts.descending("created_at");
        query.skip = skip;
        query.limit = take;
        query.projection = FILE_LIST_PROJECTION;
        var files = fileRecordCollection.find(query);

        // Build file_id -> session info map
        var fileIds = files.stream().map(f -> f.id).toList();
        var sessionMap = buildSessionMap(userId, fileIds);

        var result = new MyArtifactResult();
        result.total = count;
        result.artifacts = new ArrayList<>(files.size());
        for (var file : files) {
            var item = new MyArtifactItem();
            item.id = file.id;
            item.fileName = file.fileName;
            item.contentType = file.contentType;
            item.size = file.size;
            item.createdAt = file.createdAt;
            var sessionInfo = sessionMap.get(file.id);
            if (sessionInfo != null) {
                item.sessionId = sessionInfo.id;
                item.sessionTitle = sessionInfo.title;
            }
            result.artifacts.add(item);
        }
        return result;
    }

    private Map<String, SessionInfo> buildSessionMap(String userId, List<String> fileIds) {
        if (fileIds.isEmpty()) return Map.of();

        var filters = new ArrayList<org.bson.conversions.Bson>();
        filters.add(Filters.eq("user_id", userId));
        if (fileIds.size() == 1) {
            filters.add(Filters.eq("artifacts.file_id", fileIds.get(0)));
        } else {
            filters.add(Filters.in("artifacts.file_id", fileIds.toArray(new String[0])));
        }

        var query = new Query();
        query.filter = Filters.and(filters);
        query.sort = Sorts.descending("last_message_at");
        query.limit = Math.max(fileIds.size() * 3, 100);
        query.projection = SESSION_LOOKUP_PROJECTION;
        var sessions = chatSessionCollection.find(query);

        var fileIdSet = new HashSet<>(fileIds);
        Map<String, SessionInfo> map = new LinkedHashMap<>();
        for (var session : sessions) {
            if (session.artifacts == null) continue;
            for (var artifact : session.artifacts) {
                if (artifact.fileId != null && fileIdSet.contains(artifact.fileId)) {
                    map.putIfAbsent(artifact.fileId, new SessionInfo(session.id, session.title));
                }
            }
        }
        return map;
    }

    private List<String> findFileIdsByAgent(String userId, String agentId) {
        var filters = new ArrayList<org.bson.conversions.Bson>();
        filters.add(Filters.eq("agent_id", agentId));
        if (userId != null && !userId.isBlank()) {
            filters.add(Filters.eq("user_id", userId));
        }

        var query = new Query();
        query.filter = Filters.and(filters);
        query.projection = SESSION_AGENT_LOOKUP_PROJECTION;
        var sessions = chatSessionCollection.find(query);

        var fileIds = new ArrayList<String>();
        for (var session : sessions) {
            if (session.artifacts == null) continue;
            for (var artifact : session.artifacts) {
                if (artifact.fileId != null) fileIds.add(artifact.fileId);
            }
        }
        return fileIds;
    }

    public SharedArtifactResult listShared(Integer offset, Integer limit, String name, String userId, String agentId) {
        int skip = offset != null && offset >= 0 ? offset : 0;
        int take = limit != null && limit > 0 ? Math.min(limit, MAX_LIMIT) : DEFAULT_LIMIT;

        var filterList = new ArrayList<org.bson.conversions.Bson>();
        filterList.add(Filters.type("share_token", "string"));

        if (name != null && !name.isBlank()) {
            filterList.add(Filters.regex("file_name", Pattern.quote(name), "i"));
        }
        if (userId != null && !userId.isBlank()) {
            filterList.add(Filters.eq("user_id", userId));
        }
        if (agentId != null && !agentId.isBlank()) {
            var fileIdsByAgent = findFileIdsByAgent(userId, agentId);
            if (fileIdsByAgent.isEmpty()) {
                var empty = new SharedArtifactResult();
                empty.total = 0;
                empty.artifacts = List.of();
                return empty;
            }
            filterList.add(Filters.in("_id", fileIdsByAgent.toArray(new String[0])));
        }

        var filter = Filters.and(filterList);
        var count = fileRecordCollection.count(filter);

        var query = new Query();
        query.filter = filter;
        query.sort = Sorts.descending("shared_at");
        query.skip = skip;
        query.limit = take;
        query.projection = FILE_LIST_PROJECTION;
        var files = fileRecordCollection.find(query);

        var result = new SharedArtifactResult();
        result.total = count;
        result.artifacts = new ArrayList<>(files.size());
        for (var file : files) {
            var item = new SharedArtifactItem();
            item.id = file.id;
            item.fileName = file.fileName;
            item.contentType = file.contentType;
            item.size = file.size;
            item.userId = file.userId;
            item.createdAt = file.createdAt;
            item.sharedAt = file.sharedAt;
            result.artifacts.add(item);
        }
        return result;
    }

    public List<SharedArtifactUser> listSharedUsers() {
        var pipeline = List.of(
            Aggregates.match(Filters.type("share_token", "string")),
            Aggregates.group("$user_id"),
            Aggregates.sort(Sorts.ascending("_id"))
        );
        var aggregate = new Aggregate<Document>();
        aggregate.resultClass = Document.class;
        aggregate.pipeline = pipeline;
        var docs = fileRecordCollection.aggregate(aggregate);

        var userIds = docs.stream().map(doc -> doc.getString("_id")).filter(Objects::nonNull).toList();
        if (userIds.isEmpty()) return List.of();

        var userMap = new HashMap<String, String>();
        var query = new Query();
        query.filter = Filters.in("_id", userIds.toArray(new String[0]));
        query.projection = Projections.include("_id", "name");
        for (var user : userCollection.find(query)) {
            if (user.id != null && user.name != null) userMap.put(user.id, user.name);
        }

        var result = new ArrayList<SharedArtifactUser>(userIds.size());
        for (var userId : userIds) {
            result.add(new SharedArtifactUser(userId, userMap.getOrDefault(userId, userId)));
        }
        return result;
    }

    public static class MyArtifactResult {
        public long total;
        public List<MyArtifactItem> artifacts;
    }

    public static class MyArtifactItem {
        public String id;
        public String fileName;
        public String contentType;
        public Long size;
        public java.time.ZonedDateTime createdAt;
        public String sessionId;
        public String sessionTitle;
    }

    public static class SharedArtifactResult {
        public long total;
        public List<SharedArtifactItem> artifacts;
    }

    public static class SharedArtifactItem {
        public String id;
        public String fileName;
        public String contentType;
        public Long size;
        public String userId;
        public java.time.ZonedDateTime createdAt;
        public java.time.ZonedDateTime sharedAt;
    }

    private record SessionInfo(String id, String title) {
    }

    public record SharedArtifactUser(String userId, String name) {
    }
}
