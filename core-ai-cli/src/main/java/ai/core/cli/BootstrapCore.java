package ai.core.cli;

import ai.core.agent.SubAgentConfig;
import ai.core.bootstrap.BootstrapResult;
import ai.core.bootstrap.PropertiesFileSource;
import ai.core.cli.remote.A2ARemoteAgentConfig;
import ai.core.cli.remote.A2ARemoteServerConfig;
import ai.core.session.FileSessionPersistence;
import ai.core.session.SessionManager;
import ai.core.session.ToolPermissionStore;

import java.util.List;
import java.util.Map;

record BootstrapCore(PropertiesFileSource props, BootstrapResult result, int maxTurn,
                     boolean memoryEnabled, boolean dailyLogsEnabled, boolean coding,
                     boolean todoV2Enabled, List<A2ARemoteAgentConfig> remoteAgents,
                     List<A2ARemoteServerConfig> remoteServers, FileSessionPersistence sessionPersistence,
                     SessionManager sessionManager, ToolPermissionStore permissionStore,
                     Map<String, SubAgentConfig> subAgentConfigs) {
}
