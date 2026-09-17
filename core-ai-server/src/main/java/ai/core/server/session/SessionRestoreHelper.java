package ai.core.server.session;

import ai.core.agent.Agent;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.tool.ToolRegistryService;
import ai.core.server.util.IdLists;
import ai.core.session.InProcessAgentSession;
import core.framework.mongo.MongoCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Rehydrates a rebuilt session from the persisted session state: the display history handed back to the agent
 * plus the tools, skills and sub-agents the user loaded dynamically during the original conversation.
 *
 * @author stephen
 */
class SessionRestoreHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionRestoreHelper.class);

    private final ChatMessageService chatMessageService;
    private final ToolRegistryService toolRegistryService;
    private final SessionSkillManager skillManager;
    private final SessionSubAgentManager subAgentManager;
    private final MongoCollection<AgentDefinition> agentDefinitionCollection;

    SessionRestoreHelper(ChatMessageService chatMessageService, ToolRegistryService toolRegistryService,
                         SessionSkillManager skillManager, SessionSubAgentManager subAgentManager,
                         MongoCollection<AgentDefinition> agentDefinitionCollection) {
        this.chatMessageService = chatMessageService;
        this.toolRegistryService = toolRegistryService;
        this.skillManager = skillManager;
        this.subAgentManager = subAgentManager;
        this.agentDefinitionCollection = agentDefinitionCollection;
    }

    void restoreDynamicallyLoaded(SessionState state, String sessionId, InProcessAgentSession session,
                                  String callerUserId) {
        if (state == null) return;
        if (state.tools != null && !state.tools.isEmpty()) {
            try {
                LOGGER.info("restore tools: {} ref(s) to resolve for session {}, refs={}", state.tools.size(), sessionId, state.tools);
                var resolved = toolRegistryService.resolveToolRefs(state.tools, sessionId, callerUserId);
                if (!resolved.isEmpty()) {
                    session.loadTools(resolved);
                    LOGGER.info("restored {} dynamically loaded tools for session {}", resolved.size(), sessionId);
                } else {
                    LOGGER.warn("restore tools: resolution returned empty for {} ref(s), sessionId={}, refs={}", state.tools.size(), sessionId, state.tools);
                }
            } catch (Exception e) {
                LOGGER.warn("failed to restore dynamically loaded tools, sessionId={}", sessionId, e);
            }
        }
        if (state.skillIds != null && !state.skillIds.isEmpty()) {
            try {
                skillManager.applyCallerSkillsToSession(session, state.skillIds, callerUserId);
                LOGGER.info("restored {} dynamically loaded skills for session {}", state.skillIds.size(), sessionId);
            } catch (Exception e) {
                LOGGER.warn("failed to restore dynamically loaded skills, sessionId={}", sessionId, e);
            }
        }
        if (state.subAgentIds != null && !state.subAgentIds.isEmpty()) {
            try {
                var definitions = IdLists.clean(state.subAgentIds).stream()
                        .map(id -> agentDefinitionCollection.get(id).orElse(null))
                        .filter(def -> def != null)
                        .toList();
                if (!definitions.isEmpty()) {
                    subAgentManager.applySubAgentsToSession(session, definitions, callerUserId);
                    LOGGER.info("restored {} dynamically loaded sub-agents for session {}", definitions.size(), sessionId);
                }
            } catch (Exception e) {
                LOGGER.warn("failed to restore dynamically loaded sub-agents, sessionId={}", sessionId, e);
            }
        }
    }

    void restoreAgentHistory(Agent agent, String sessionId) {
        try {
            var records = chatMessageService.history(sessionId);
            if (records.isEmpty()) return;
            List<Message> restored = new ArrayList<>(records.size());
            for (var record : records) {
                if (record.content == null || record.content.isBlank()) continue;
                var role = "user".equals(record.role) ? RoleType.USER : RoleType.ASSISTANT;
                restored.add(Message.of(role, record.content));
            }
            if (!restored.isEmpty()) {
                agent.restoreHistory(restored);
                LOGGER.info("restored {} historical messages for session {}", restored.size(), sessionId);
            }
        } catch (Exception e) {
            LOGGER.warn("failed to restore agent history, sessionId={}", sessionId, e);
        }
    }
}
