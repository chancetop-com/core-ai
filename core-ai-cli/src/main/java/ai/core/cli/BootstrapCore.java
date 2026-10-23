package ai.core.cli;

import ai.core.agent.SubAgentConfig;
import ai.core.bootstrap.BootstrapResult;
import ai.core.bootstrap.PropertiesFileSource;
import ai.core.cli.a2a.A2ARemoteAgentConfig;
import ai.core.cli.a2a.A2ARemoteServerConfig;
import ai.core.cli.helper.HelperEntry;
import ai.core.cli.media.MediaModelCatalog;
import ai.core.media.MediaProvider;
import ai.core.session.FileSessionPersistence;
import ai.core.session.SessionManager;
import ai.core.session.ToolPermissionStore;

import java.util.List;
import java.util.Map;
import java.util.Set;

record BootstrapCore(PropertiesFileSource props, BootstrapResult result, int maxTurn,
                     boolean memoryEnabled, boolean dailyLogsEnabled, boolean coding,
                     boolean todoV2Enabled, List<A2ARemoteAgentConfig> remoteAgents,
                     List<A2ARemoteServerConfig> remoteServers, FileSessionPersistence sessionPersistence,
                     SessionManager sessionManager, ToolPermissionStore permissionStore,
                      Map<String, SubAgentConfig> subAgentConfigs, boolean a2aAutoDiscover,
                       MediaProvider mediaProvider, MediaProvider imageMediaProvider, MediaProvider videoMediaProvider,
                       String defaultImageModel, String defaultVideoModel, String defaultImageEditModel,
                      List<HelperEntry> helpers, MediaModelCatalog mediaCatalog, Set<String> disabledTools) {
}
