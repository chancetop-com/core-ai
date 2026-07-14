package ai.core.server.artifact;

import ai.core.server.domain.ChatSession;
import ai.core.server.domain.FileRecord;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class ArtifactService {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    @Inject
    MongoCollection<FileRecord> fileRecordCollection;

    @Inject
    MongoCollection<ChatSession> chatSessionCollection;

    public MyArtifactResult listMy(String userId, Integer offset, Integer limit) {
        int skip = offset != null && offset >= 0 ? offset : 0;
        int take = limit != null && limit > 0 ? Math.min(limit, MAX_LIMIT) : DEFAULT_LIMIT;

        var count = fileRecordCollection.count(Filters.eq("user_id", userId));

        var query = new Query();
        query.filter = Filters.eq("user_id", userId);
        query.sort = Sorts.descending("created_at");
        query.skip = skip;
        query.limit = take;
        var files = fileRecordCollection.find(query);

        // Build file_id -> session info map
        var fileIds = files.stream().map(f -> f.id).toList();
        var sessionMap = buildSessionMap(userId, fileIds);

        var result = new MyArtifactResult();
        result.total = count;
        result.artifacts = new ArrayList<>();
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
        var sessions = chatSessionCollection.find(query);

        Map<String, SessionInfo> map = new LinkedHashMap<>();
        for (var session : sessions) {
            if (session.artifacts == null) continue;
            for (var artifact : session.artifacts) {
                if (artifact.fileId != null && fileIds.contains(artifact.fileId)) {
                    map.putIfAbsent(artifact.fileId, new SessionInfo(session.id, session.title));
                }
            }
        }
        return map;
    }

    public SharedArtifactResult listShared(Integer offset, Integer limit, String name, String userId) {
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

        var filter = Filters.and(filterList);
        var count = fileRecordCollection.count(filter);

        var query = new Query();
        query.filter = filter;
        query.sort = Sorts.descending("shared_at");
        query.skip = skip;
        query.limit = take;
        var files = fileRecordCollection.find(query);

        var result = new SharedArtifactResult();
        result.total = count;
        result.artifacts = new ArrayList<>();
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
}
