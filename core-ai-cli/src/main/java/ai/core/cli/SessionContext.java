package ai.core.cli;

import ai.core.bootstrap.BootstrapResult;
import ai.core.bootstrap.PropertiesFileSource;
import ai.core.cli.config.ModelRegistry;
import ai.core.cli.memory.MdMemoryProvider;
import ai.core.cli.remote.A2ARemoteAgentConfig;
import ai.core.cli.remote.A2ARemoteServerConfig;
import ai.core.session.FileSessionPersistence;
import ai.core.session.SessionManager;
import ai.core.session.ToolPermissionStore;

import java.util.List;

record SessionContext(BootstrapResult result, PropertiesFileSource props, int maxTurn,
                      FileSessionPersistence sessionPersistence, SessionManager sessionManager, String modelName,
                      String currentSessionId, ToolPermissionStore permissionStore, MdMemoryProvider noteMemory,
                      ModelRegistry modelRegistry, boolean memoryEnabled, boolean coding, boolean todoV2Enabled,
                      List<A2ARemoteAgentConfig> remoteAgents, List<A2ARemoteServerConfig> remoteServers) {
}
