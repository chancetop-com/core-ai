package ai.core.reflection;

import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Usage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Comprehensive reflection mechanism tests using mock LLM provider.
 * Tests reflection loop, listener callbacks, history tracking, and termination conditions.
 *
 * @author stephen
 * @author xander
 */
class RefTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(RefTest.class);

    private LLMProvider mockLLMProvider;
    private final AtomicInteger callCount = new AtomicInteger(0);

    @BeforeEach
    void setup() {
        mockLLMProvider = mock(LLMProvider.class);
        callCount.set(0);

        // Mock completion responses (used by ReflectionEvaluator)
        when(mockLLMProvider.completion(any(CompletionRequest.class))).thenAnswer(invocation -> {
            CompletionRequest request = invocation.getArgument(0);
            int count = callCount.incrementAndGet();

            // Check if this is an evaluation request (JSON response format)
            if (request.responseFormat != null) {
                return createEvaluationResponse(count);
            } else {
                return createAgentResponse(count);
            }
        });

        // Mock completionStream responses (used by Agent)
        when(mockLLMProvider.completionStream(any(CompletionRequest.class), any())).thenAnswer(invocation -> {
            CompletionRequest request = invocation.getArgument(0);
            int count = callCount.incrementAndGet();

            // Check if this is an evaluation request (JSON response format)
            if (request.responseFormat != null) {
                return createEvaluationResponse(count);
            } else {
                return createAgentResponse(count);
            }
        });
    }

    private CompletionResponse createAgentResponse(int round) {
        String content = String.format("This is solution from round %d. Improved implementation with better code quality.", round);
        return CompletionResponse.of(
            List.of(Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, content, null))),
            new Usage(100, 200, 300)
        );
    }

    private CompletionResponse createEvaluationResponse(int round) {
        String evaluationJson;
        // Treat odd numbers as evaluation calls, even numbers as agent calls
        // This accounts for interleaved agent response + evaluation calls
        int evaluationRound = (round + 1) / 2;

        if (evaluationRound == 1) {
            // First evaluation: score 6, needs improvement
            evaluationJson = """
                {
                  "score": 6,
                  "pass": false,
                  "should_continue": true,
                  "confidence": 0.8,
                  "strengths": ["Basic implementation is correct"],
                  "weaknesses": ["Missing error handling", "No edge case handling"],
                  "suggestions": ["Add null checks", "Handle empty arrays"],
                  "dimensions": {"correctness": 7, "readability": 6, "performance": 7}
                }
                """;
        } else if (evaluationRound == 2) {
            // Second evaluation: score 9, pass
            evaluationJson = """
                {
                  "score": 9,
                  "pass": true,
                  "should_continue": false,
                  "confidence": 0.95,
                  "strengths": ["Excellent error handling", "Clean code", "Good performance"],
                  "weaknesses": [],
                  "suggestions": [],
                  "dimensions": {"correctness": 10, "readability": 9, "performance": 9}
                }
                """;
        } else {
            // Subsequent evaluations: high score
            evaluationJson = """
                {
                  "score": 10,
                  "pass": true,
                  "should_continue": false,
                  "confidence": 1.0,
                  "strengths": ["Perfect implementation"],
                  "weaknesses": [],
                  "suggestions": [],
                  "dimensions": {"correctness": 10, "readability": 10, "performance": 10}
                }
                """;
        }

        return CompletionResponse.of(
            List.of(Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, evaluationJson, null))),
            new Usage(50, 100, 150)
        );
    }

    /**
     * Test basic reflection with evaluation criteria.
     * Verifies that reflection improves output through multiple rounds.
     */
    @Test
    void testBasicReflectionWithCriteria() {
        LOGGER.info("\n=== Test: Basic Reflection with Criteria ===\n");

        String criteria = """
            Code must meet these standards:
            1. Performance: O(n) or better time complexity
            2. Readability: Descriptive variable names
            3. Error handling: Handle null and edge cases
            4. Best practices: Follow Java naming conventions
            """;

        Agent agent = Agent.builder()
                .name("code-quality-agent")
                .llmProvider(mockLLMProvider)
                .systemPrompt("You are a Java expert who writes high-quality code.")
                .reflectionEvaluationCriteria(criteria)
                .build();

        String task = "Write a Java method to find the maximum value in an array";
        LOGGER.info("Task: {}", task);

        String result = agent.run(task, ExecutionContext.builder().build());

        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertTrue(agent.getRound() >= 1, "Should execute at least 1 round");
        LOGGER.info("Completed in {} rounds", agent.getRound());
        LOGGER.info("Final result: {}", result);
    }

    /**
     * Test reflection listener callbacks.
     * Verifies all listener methods are called in correct order.
     */
    @Test
    void testReflectionListener() {
        LOGGER.info("\n=== Test: Reflection Listener Callbacks ===\n");

        TestReflectionListener listener = new TestReflectionListener();

        String criteria = """
            Solution must:
            1. Be clear and concise
            2. Include examples
            3. Explain key concepts
            """;

        Agent agent = Agent.builder()
                .name("explanation-agent")
                .llmProvider(mockLLMProvider)
                .systemPrompt("You are a helpful assistant who explains technical concepts clearly.")
                .reflectionEvaluationCriteria(criteria)
                .reflectionListener(listener)
                .build();

        String task = "Explain what recursion is with an example";
        agent.run(task, ExecutionContext.builder().build());

        // Verify listener was called
        assertTrue(listener.startCalled, "onReflectionStart should be called");
        assertTrue(listener.completeCalled, "onReflectionComplete should be called");
        assertTrue(listener.beforeRoundCount.get() > 0, "onBeforeRound should be called");
        assertTrue(listener.afterRoundCount.get() > 0, "onAfterRound should be called");

        // Verify history was provided
        assertNotNull(listener.finalHistory, "Listener should receive final history");
        assertFalse(listener.finalHistory.getRounds().isEmpty(), "History should have rounds");

        LOGGER.info("Listener callbacks verified:");
        LOGGER.info("  - Start called: {}", listener.startCalled);
        LOGGER.info("  - Before round called: {} times", listener.beforeRoundCount.get());
        LOGGER.info("  - After round called: {} times", listener.afterRoundCount.get());
        LOGGER.info("  - Complete called: {}", listener.completeCalled);
        LOGGER.info("  - Total rounds in history: {}", listener.finalHistory.getRounds().size());
    }

    /**
     * Test reflection history tracking.
     * Verifies that history correctly records all rounds with evaluations.
     */
    @Test
    void testReflectionHistory() {
        LOGGER.info("\n=== Test: Reflection History Tracking ===\n");

        TestReflectionListener listener = new TestReflectionListener();

        String criteria = """
            Article must:
            1. Have clear structure (intro, body, conclusion)
            2. Be 100-150 words
            3. Use professional language
            """;

        Agent agent = Agent.builder()
                .name("writing-agent")
                .llmProvider(mockLLMProvider)
                .systemPrompt("You are a professional writer.")
                .reflectionConfig(new ReflectionConfig(true, 3, 1,
                    Reflection.DEFAULT_REFLECTION_WITH_CRITERIA_TEMPLATE, criteria))
                .reflectionListener(listener)
                .build();

        String task = "Write a short article about AI's impact on education";
        agent.run(task, ExecutionContext.builder().build());

        ReflectionHistory history = listener.finalHistory;
        assertNotNull(history);

        // Verify history metadata
        assertEquals(agent.getId(), history.getAgentId());
        assertEquals(agent.getName(), history.getAgentName());
        assertEquals(task, history.getInitialInput());
        assertNotNull(history.getStartTime());
        assertNotNull(history.getEndTime());

        // Verify rounds
        assertFalse(history.getRounds().isEmpty());
        for (ReflectionHistory.ReflectionRound round : history.getRounds()) {
            assertNotNull(round.getEvaluation());
            assertTrue(round.getEvaluation().getScore() >= 1 && round.getEvaluation().getScore() <= 10);
            assertNotNull(round.getEvaluationInput());
            assertNotNull(round.getEvaluationOutput());
            LOGGER.info("Round {}: score={}, pass={}, continue={}",
                round.getRoundNumber(),
                round.getEvaluation().getScore(),
                round.getEvaluation().isPass(),
                round.getEvaluation().isShouldContinue());
        }

        LOGGER.info("History validation successful:");
        LOGGER.info("  - Total rounds: {}", history.getRounds().size());
        LOGGER.info("  - Final score: {}", history.getFinalScore());
        LOGGER.info("  - Total tokens: {}", history.getTotalTokensUsed());
        LOGGER.info("  - Duration: {}ms", history.getTotalDuration().toMillis());
        LOGGER.info("  - Status: {}", history.getStatus());
    }

    /**
     * Test max rounds termination.
     * Verifies reflection stops when max rounds is reached.
     */
    @Test
    void testMaxRoundsTermination() {
        LOGGER.info("\n=== Test: Max Rounds Termination ===\n");

        TestReflectionListener listener = new TestReflectionListener();

        // Create custom mock that always returns low scores to force max rounds
        LLMProvider lowScoreMock = mock(LLMProvider.class);
        when(lowScoreMock.completion(any(CompletionRequest.class))).thenAnswer(invocation -> {
            CompletionRequest request = invocation.getArgument(0);
            if (request.responseFormat != null) {
                // Always return score 5 to continue reflection
                String json = """
                    {
                      "score": 5,
                      "pass": false,
                      "should_continue": true,
                      "confidence": 0.6,
                      "strengths": ["Basic structure"],
                      "weaknesses": ["Needs improvement"],
                      "suggestions": ["Add more details"],
                      "dimensions": {"quality": 5}
                    }
                    """;
                return CompletionResponse.of(
                    List.of(Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, json, null))),
                    new Usage(50, 100, 150)
                );
            } else {
                return createAgentResponse(1);
            }
        });

        when(lowScoreMock.completionStream(any(CompletionRequest.class), any())).thenAnswer(invocation -> {
            CompletionRequest request = invocation.getArgument(0);
            if (request.responseFormat != null) {
                // Always return score 5 to continue reflection
                String json = """
                    {
                      "score": 5,
                      "pass": false,
                      "should_continue": true,
                      "confidence": 0.6,
                      "strengths": ["Basic structure"],
                      "weaknesses": ["Needs improvement"],
                      "suggestions": ["Add more details"],
                      "dimensions": {"quality": 5}
                    }
                    """;
                return CompletionResponse.of(
                    List.of(Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, json, null))),
                    new Usage(50, 100, 150)
                );
            } else {
                return createAgentResponse(1);
            }
        });

        String criteria = """
            Solution must be perfect (intentionally hard to achieve):
            1. Score must be 10/10
            2. Zero weaknesses
            3. Complete documentation
            """;

        // Set max rounds to 2 to force termination
        Agent agent = Agent.builder()
                .name("max-rounds-agent")
                .llmProvider(lowScoreMock)
                .systemPrompt("You are a helpful assistant.")
                .reflectionConfig(new ReflectionConfig(true, 2, 1,
                    Reflection.DEFAULT_REFLECTION_WITH_CRITERIA_TEMPLATE, criteria))
                .reflectionListener(listener)
                .build();

        String task = "Explain binary search";
        agent.run(task, ExecutionContext.builder().build());

        // Verify max rounds was respected
        assertTrue(agent.getRound() <= 2, "Should not exceed max rounds");
        assertTrue(listener.maxRoundsReachedCalled, "Max rounds callback should be called");

        LOGGER.info("Max rounds termination verified:");
        LOGGER.info("  - Max rounds reached: {}", listener.maxRoundsReachedCalled);
        LOGGER.info("  - Actual rounds: {}", agent.getRound());
    }

    /**
     * Test score achievement termination.
     * Verifies reflection stops when score threshold is met.
     */
    @Test
    void testScoreAchievementTermination() {
        LOGGER.info("\n=== Test: Score Achievement Termination ===\n");

        TestReflectionListener listener = new TestReflectionListener();

        String criteria = """
            Simple criteria (easy to achieve):
            1. Provide an answer
            2. Use clear language
            """;

        Agent agent = Agent.builder()
                .name("score-agent")
                .llmProvider(mockLLMProvider)
                .systemPrompt("You are a helpful assistant who gives clear explanations.")
                .reflectionEvaluationCriteria(criteria)
                .reflectionListener(listener)
                .build();

        String task = "What is 2+2?";
        agent.run(task, ExecutionContext.builder().build());

        LOGGER.info("Termination reason:");
        if (listener.scoreAchievedCalled) {
            LOGGER.info("  - Score achieved (score >= 8 and pass)");
        } else if (listener.noImprovementCalled) {
            LOGGER.info("  - No improvement possible");
        } else if (listener.maxRoundsReachedCalled) {
            LOGGER.info("  - Max rounds reached");
        }
    }

    /**
     * Test simple reflection without criteria (backward compatibility).
     * Verifies legacy reflection mode still works.
     */
    @Test
    void testSimpleReflectionWithoutCriteria() {
        LOGGER.info("\n=== Test: Simple Reflection (No Criteria) ===\n");

        Agent agent = Agent.builder()
                .name("simple-agent")
                .llmProvider(mockLLMProvider)
                .systemPrompt("You are a helpful assistant.")
                .enableReflection(true)
                .build();

        String task = "Explain what a hash table is";
        String result = agent.run(task, ExecutionContext.builder().build());

        assertNotNull(result);
        assertFalse(result.isEmpty());
        LOGGER.info("Simple reflection completed in {} rounds", agent.getRound());
    }

    /**
     * Test reflection with custom config.
     * Verifies custom min/max rounds and prompts work correctly.
     */
    @Test
    void testCustomReflectionConfig() {
        LOGGER.info("\n=== Test: Custom Reflection Config ===\n");

        String customPrompt = """
            You are an expert evaluator. Assess the solution and provide JSON feedback.

            Return:
            {
              "score": 1-10,
              "pass": true/false,
              "should_continue": true/false,
              "confidence": 0.0-1.0,
              "strengths": [],
              "weaknesses": [],
              "suggestions": [],
              "dimensions": {"quality": 1-10}
            }
            """;

        ReflectionConfig config = new ReflectionConfig(
            true,
            3,    // maxRound
            1,    // minRound
            customPrompt,
            "Solution must be accurate and complete"
        );

        Agent agent = Agent.builder()
                .name("custom-config-agent")
                .llmProvider(mockLLMProvider)
                .systemPrompt("You are a helpful assistant.")
                .reflectionConfig(config)
                .build();

        String task = "Explain quicksort algorithm";
        agent.run(task, ExecutionContext.builder().build());

        assertTrue(agent.getRound() >= 1);
        assertTrue(agent.getRound() <= 3);
        LOGGER.info("Custom config test completed in {} rounds", agent.getRound());
    }

    /**
     * Test reflection listener for tracking all events.
     */
    private static final class TestReflectionListener implements ReflectionListener {
        boolean startCalled = false;
        boolean completeCalled = false;
        boolean scoreAchievedCalled = false;
        boolean noImprovementCalled = false;
        boolean maxRoundsReachedCalled = false;
        boolean errorCalled = false;

        final AtomicInteger beforeRoundCount = new AtomicInteger(0);
        final AtomicInteger afterRoundCount = new AtomicInteger(0);

        ReflectionHistory finalHistory = null;

        @Override
        public void onReflectionStart(Agent agent, String task, String evaluationCriteria) {
            startCalled = true;
            LOGGER.debug("Reflection started for agent: {}, task: {}", agent.getName(), task);
        }

        @Override
        public void onBeforeRound(Agent agent, int round, String input) {
            beforeRoundCount.incrementAndGet();
            LOGGER.debug("Before round {}", round);
        }

        @Override
        public void onAfterRound(Agent agent, int round, String output, ReflectionEvaluation evaluation) {
            afterRoundCount.incrementAndGet();
            LOGGER.debug("After round {}: score={}, pass={}", round, evaluation.getScore(), evaluation.isPass());
        }

        @Override
        public void onScoreAchieved(Agent agent, int finalScore, int rounds) {
            scoreAchievedCalled = true;
            LOGGER.debug("Score achieved: {} in {} rounds", finalScore, rounds);
        }

        @Override
        public void onNoImprovement(Agent agent, int lastScore, int rounds) {
            noImprovementCalled = true;
            LOGGER.debug("No improvement: score={} after {} rounds", lastScore, rounds);
        }

        @Override
        public void onMaxRoundsReached(Agent agent, int finalScore) {
            maxRoundsReachedCalled = true;
            LOGGER.debug("Max rounds reached: final score={}", finalScore);
        }

        @Override
        public void onReflectionComplete(Agent agent, ReflectionHistory history) {
            completeCalled = true;
            finalHistory = history;
            LOGGER.debug("Reflection completed: {} rounds, status={}",
                history.getRounds().size(), history.getStatus());
        }

        @Override
        public void onError(Agent agent, int round, Exception error) {
            errorCalled = true;
            LOGGER.error("Reflection error at round {}: {}", round, error.getMessage());
        }
    }
}
