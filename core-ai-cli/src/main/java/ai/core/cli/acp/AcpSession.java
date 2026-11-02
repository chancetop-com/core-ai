package ai.core.cli.acp;

import ai.core.agent.Agent;
import ai.core.session.FileSessionPersistence;
import ai.core.session.SessionLock;
import com.agentclientprotocol.sdk.agent.SyncPromptContext;

import java.util.concurrent.atomic.AtomicReference;

record AcpSession(Agent agent, String sessionId,
                  AtomicReference<SyncPromptContext> currentContext,
                  FileSessionPersistence persistence,
                  SessionLock lock) {
}
