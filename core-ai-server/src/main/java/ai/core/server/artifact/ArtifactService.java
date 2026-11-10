package ai.core.server.artifact;

import ai.core.server.file.FileRecordQuery;
import ai.core.server.file.FileRecordStore;
import ai.core.server.session.ChatSessionQuery;
import ai.core.server.session.ChatSessionStore;
import core.framework.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ArtifactService {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    // listing only exposes metadata; legacy inline content in `data` is never part of a list payload
    @Inject
    FileRecordStore fileRecordStore;

    @Inject
    ChatSessionStore chatSessionStore;

    @Inject
    ai.core.server.user.UserStore userStore;

    public MyArtifactResult listMy(String userId, Integer offset, Integer limit, String agentId) {
        int skip = offset != null && offset >= 0 ? offset : 0;
        int take = limit != null && limit > 0 ? Math.min(limit, MAX_LIMIT) : DEFAULT_LIMIT;

        var query = new FileRecordQuery();
        query.userId = userId;
        query.excludeData = true;
        if (agentId != null && !agentId.isBlank()) {
            var fileIdsByAgent = findFileIdsByAgent(userId, agentId);
            if (fileIdsByAgent.isEmpty()) {
                var empty = new MyArtifactResult();
                empty.total = 0;
                empty.artifacts = List.of();
                return empty;
            }
            query.ids = fileIdsByAgent;
        }

        var count = fileRecordStore.count(query);

        query.offset = skip;
        query.limit = take;
        var files = fileRecordStore.find(query);

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

        var query = new ChatSessionQuery();
        query.userId = userId;
        query.artifactFileIds = fileIds;
        query.limit = Math.max(fileIds.size() * 3, 100);
        var sessions = chatSessionStore.find(query);

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
        var query = new ChatSessionQuery();
        query.agentIds = List.of(agentId);
        if (userId != null && !userId.isBlank()) query.userId = userId;
        query.limit = Integer.MAX_VALUE;
        var sessions = chatSessionStore.find(query);

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

        var query = new FileRecordQuery();
        query.sharedOnly = Boolean.TRUE;
        query.excludeData = true;
        query.fileNameKeyword = name;
        if (userId != null && !userId.isBlank()) query.userId = userId;
        if (agentId != null && !agentId.isBlank()) {
            var fileIdsByAgent = findFileIdsByAgent(userId, agentId);
            if (fileIdsByAgent.isEmpty()) {
                var empty = new SharedArtifactResult();
                empty.total = 0;
                empty.artifacts = List.of();
                return empty;
            }
            query.ids = fileIdsByAgent;
        }

        var count = fileRecordStore.count(query);

        query.sortField = "shared_at";
        query.offset = skip;
        query.limit = take;
        var files = fileRecordStore.find(query);

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
        var userIds = fileRecordStore.sharedUserIds();
        if (userIds.isEmpty()) return List.of();

        var userMap = new HashMap<String, String>();
        for (var user : userStore.listByIds(userIds)) {
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
