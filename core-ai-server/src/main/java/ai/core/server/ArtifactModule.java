package ai.core.server;

import ai.core.api.server.ArtifactWebService;
import ai.core.server.artifact.ArtifactService;
import ai.core.server.artifact.ChatArtifactSetup;
import ai.core.server.artifact.PublicUrlConfiguration;
import ai.core.server.web.ArtifactWebServiceImpl;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class ArtifactModule extends Module {
    @Override
    protected void initialize() {
        bind(new PublicUrlConfiguration(property("sys.public.url").orElse("http://localhost:8080")));
        bind(ChatArtifactSetup.class);
        bind(ArtifactService.class);
        api().service(ArtifactWebService.class, bind(ArtifactWebServiceImpl.class));
    }
}
