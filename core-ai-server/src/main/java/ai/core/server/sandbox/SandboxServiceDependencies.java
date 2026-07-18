package ai.core.server.sandbox;

import ai.core.server.blob.ObjectStorageService;
import ai.core.server.file.FileService;
import ai.core.server.sandbox.snapshot.SandboxSnapshotService;
import redis.clients.jedis.JedisPool;

/**
 * @author stephen
 */
public record SandboxServiceDependencies(JedisPool jedisPool, SandboxSnapshotService snapshotService,
                                  ObjectStorageService storageService, FileService fileService) {
}
