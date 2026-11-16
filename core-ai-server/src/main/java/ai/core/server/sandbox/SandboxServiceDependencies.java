package ai.core.server.sandbox;

import ai.core.server.blob.ObjectStorageServiceResolver;
import ai.core.server.file.FileService;
import ai.core.server.domain.SessionAttachmentRefRepository;
import ai.core.server.sandbox.snapshot.SandboxSnapshotService;
import redis.clients.jedis.JedisPool;

/**
 * @author stephen
 */
public record SandboxServiceDependencies(JedisPool jedisPool, SandboxSnapshotService snapshotService,
                                         ObjectStorageServiceResolver storageResolver, FileService fileService,
                                         SessionAttachmentRefRepository attachmentRepository,
                                         SandboxBindingStore bindingStore) {
    public SandboxServiceDependencies(JedisPool jedisPool, SandboxSnapshotService snapshotService,
                                      ObjectStorageServiceResolver storageResolver, FileService fileService,
                                      SessionAttachmentRefRepository attachmentRepository) {
        this(jedisPool, snapshotService, storageResolver, fileService, attachmentRepository, null);
    }
}
