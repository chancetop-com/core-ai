package ai.core.agent.userinput;

import ai.core.IntegrationTest;
import ai.core.agent.ExecutionContext;
import ai.core.agent.NodeStatus;
import ai.core.agent.UserInputAgent;
import ai.core.persistence.providers.TemporaryPersistenceProvider;
import core.framework.inject.Inject;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Integration test for UserInputAgent
 * Tests the agent's ability to pause for user input and resume execution
 *
 * @author stephen
 */
class UserInputAgentTest extends IntegrationTest {
    private final Logger logger = LoggerFactory.getLogger(UserInputAgentTest.class);

    @Inject
    TemporaryPersistenceProvider persistenceProvider;

    @Test
    void testUserInputAgentStartAndFinish() {
        // Step 1: Start - Agent is waiting for user input
        logger.info("=== Step 1: Start UserInputAgent ===");
        var agent = UserInputAgent.builder()
                .persistenceProvider(persistenceProvider)
                .build();

        String initialQuery = "Please provide your name";
        logger.info("Initial query: {}", initialQuery);

        String result1 = agent.run(initialQuery, ExecutionContext.empty());
        logger.info("Result after start: {}", result1);
        logger.info("Agent status: {}", agent.getNodeStatus());

        // Verify agent is in WAITING_FOR_USER_INPUT status
        assertEquals(NodeStatus.WAITING_FOR_USER_INPUT, agent.getNodeStatus(),
                     "Agent should be in WAITING_FOR_USER_INPUT status after start");
        assertEquals(initialQuery, result1, "Result should be the initial query");

        // Step 2: Save agent state
        String agentId = UUID.randomUUID().toString();
        logger.info("=== Step 2: Save agent state with id: {} ===", agentId);
        String savedId = agent.save(agentId);
        assertNotNull(savedId, "Saved ID should not be null");
        assertEquals(agentId, savedId, "Saved ID should match the provided ID");

        // Step 3: Create a new agent instance and load the saved state
        logger.info("=== Step 3: Load saved agent state ===");
        var agent2 = UserInputAgent.builder()
                .persistenceProvider(persistenceProvider)
                .build();

        agent2.load(agentId);
        logger.info("Loaded agent status: {}", agent2.getNodeStatus());

        // Verify loaded agent has the same status
        assertEquals(NodeStatus.WAITING_FOR_USER_INPUT, agent2.getNodeStatus(),
                     "Loaded agent should be in WAITING_FOR_USER_INPUT status");

        // Step 4: Finish - Provide user input and complete the agent
        logger.info("=== Step 4: Finish with user input ===");
        String userInput = "My name is Alice";
        logger.info("User input: {}", userInput);

        String result2 = agent2.run(userInput, ExecutionContext.empty());
        logger.info("Result after finish: {}", result2);
        logger.info("Agent status: {}", agent2.getNodeStatus());

        // Verify agent completed successfully
        assertEquals(NodeStatus.COMPLETED, agent2.getNodeStatus(),
                     "Agent should be in COMPLETED status after receiving user input");
        assertEquals(userInput, result2, "Result should be the user input");
        assertEquals(userInput, agent2.getOutput(), "Agent output should be the user input");
    }

    @Test
    void testUserInputAgentMultipleRounds() {
        // Test scenario: Multiple user input rounds
        logger.info("=== Testing multiple user input rounds ===");

        // Round 1
        var agent1 = UserInputAgent.builder()
                .persistenceProvider(persistenceProvider)
                .build();

        String query1 = "What is your age?";
        agent1.run(query1, ExecutionContext.empty());

        String id1 = UUID.randomUUID().toString();
        agent1.save(id1);

        var loadedAgent1 = UserInputAgent.builder()
                .persistenceProvider(persistenceProvider)
                .build();
        loadedAgent1.load(id1);

        String answer1 = "I am 25 years old";
        String result1 = loadedAgent1.run(answer1, ExecutionContext.empty());

        assertEquals(NodeStatus.COMPLETED, loadedAgent1.getNodeStatus());
        assertEquals(answer1, result1);

        // Round 2
        var agent2 = UserInputAgent.builder()
                .persistenceProvider(persistenceProvider)
                .build();

        String query2 = "What is your favorite color?";
        agent2.run(query2, ExecutionContext.empty());

        String id2 = UUID.randomUUID().toString();
        agent2.save(id2);

        var loadedAgent2 = UserInputAgent.builder()
                .persistenceProvider(persistenceProvider)
                .build();
        loadedAgent2.load(id2);

        String answer2 = "My favorite color is blue";
        String result2 = loadedAgent2.run(answer2, ExecutionContext.empty());

        assertEquals(NodeStatus.COMPLETED, loadedAgent2.getNodeStatus());
        assertEquals(answer2, result2);

        logger.info("Multiple rounds completed successfully");
    }

    @Test
    void testUserInputAgentPersistenceAndRestore() {
        // Test that agent state is correctly persisted and restored
        logger.info("=== Testing persistence and restore ===");

        var originalAgent = UserInputAgent.builder()
                .persistenceProvider(persistenceProvider)
                .build();

        String initialQuery = "Please provide feedback";
        originalAgent.run(initialQuery, ExecutionContext.empty());

        // Check initial state
        assertEquals(NodeStatus.WAITING_FOR_USER_INPUT, originalAgent.getNodeStatus());
        assertEquals(initialQuery, originalAgent.getInput());

        // Save
        String agentId = UUID.randomUUID().toString();
        originalAgent.save(agentId);

        // Load in new instance
        var restoredAgent = UserInputAgent.builder()
                .persistenceProvider(persistenceProvider)
                .build();
        restoredAgent.load(agentId);

        // Verify restored state
        assertEquals(originalAgent.getNodeStatus(), restoredAgent.getNodeStatus());
        assertEquals(originalAgent.getInput(), restoredAgent.getInput());
        assertEquals(originalAgent.getName(), restoredAgent.getName());

        // Complete with user input
        String userFeedback = "This is great!";
        restoredAgent.run(userFeedback, ExecutionContext.empty());

        assertEquals(NodeStatus.COMPLETED, restoredAgent.getNodeStatus());
        assertEquals(userFeedback, restoredAgent.getOutput());

        logger.info("Persistence and restore test completed successfully");
    }
}
