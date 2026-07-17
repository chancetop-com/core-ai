package ai.core.server;

import ai.core.api.server.ArtifactWebService;
import ai.core.server.artifact.ArtifactService;
import ai.core.server.artifact.ChatArtifactSetup;
import ai.core.server.run.SubmitArtifactsTool;
import ai.core.server.web.ArtifactWebServiceImpl;
import core.framework.module.Module;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * @author stephen
 */
public class ArtifactModule extends Module {
    @Override
    @SuppressFBWarnings("ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD")
    protected void initialize() {
        SubmitArtifactsTool.publicUrl = property("sys.public.url").orElse("http://localhost:8080");
        bind(ChatArtifactSetup.class);
        bind(ArtifactService.class);
        api().service(ArtifactWebService.class, bind(ArtifactWebServiceImpl.class));
    }
}
