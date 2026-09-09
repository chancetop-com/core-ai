package ai.core.server;

import ai.core.server.project.ProjectArtifactBinder;
import ai.core.server.project.ProjectAttributionStore;
import core.framework.module.Module;

/**
 * Attribution primitives shared by the file upload controller (ObjectStorageModule), the artifact sinks
 * (ArtifactModule / AgentRunnerModule) and the project feature itself (ProjectModule). Loaded first among
 * the platform modules: both beans depend on Mongo collections only.
 *
 * @author stephen
 */
public class ProjectAttributionModule extends Module {
    @Override
    protected void initialize() {
        bind(ProjectAttributionStore.class);
        bind(ProjectArtifactBinder.class);
    }
}
