package ai.core.server.sandbox;

import ai.core.server.blob.ObjectStorageServiceResolver;
import ai.core.server.domain.SessionAttachmentRefRepository;
import ai.core.server.file.FileService;
import ai.core.server.sandbox.snapshot.SandboxSnapshotService;

/**
 * @author stephen
 */
public record SandboxServiceDependencies(SandboxSnapshotService snapshotService,
                                         ObjectStorageServiceResolver storageResolver, FileService fileService,
                                         SessionAttachmentRefRepository attachmentRepository,
                                         SandboxBindingStore bindingStore) {
    public SandboxServiceDependencies(SandboxSnapshotService snapshotService,
                                      ObjectStorageServiceResolver storageResolver, FileService fileService,
                                      SessionAttachmentRefRepository attachmentRepository) {
        this(snapshotService, storageResolver, fileService, attachmentRepository, null);
    }
}
