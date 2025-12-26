package ai.core.tool.function;

import ai.core.agent.ExecutionContext;
import ai.core.api.tool.function.CoreAiMethod;
import ai.core.api.tool.function.CoreAiParameter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class ExecutionContextInjectionTest {
    private ContextAwareService service;

    @BeforeEach
    void setUp() {
        service = new ContextAwareService();
    }

    @Test
    void testExecutionContextInjection() {
        var functions = Functions.from(service, "getUserInfo");
        assertEquals(1, functions.size());

        var function = functions.getFirst();
        assertEquals("getUserInfo", function.getName());

        // ExecutionContext should not appear in parameters
        assertEquals(1, function.getParameters().size());
        assertEquals("userId", function.getParameters().getFirst().getName());

        // Create context with session and user info
        var context = ExecutionContext.builder()
                .sessionId("session-123")
                .userId("user-456")
                .customVariable("role", "admin")
                .build();

        // Execute with context
        var result = function.execute("{\"userId\":\"test-user\"}", context);

        assertTrue(result.isCompleted());
        assertNotNull(result.getResult());
        assertTrue(result.getResult().contains("session-123"));
        assertTrue(result.getResult().contains("user-456"));
        assertTrue(result.getResult().contains("test-user"));
    }

    @Test
    void testExecutionContextAtDifferentPositions() {
        var functions = Functions.from(service, "processWithContextFirst", "processWithContextMiddle", "processWithContextLast");
        assertEquals(3, functions.size());

        var context = ExecutionContext.builder()
                .sessionId("sess-abc")
                .userId("user-xyz")
                .build();

        // Test context as first parameter
        var funcFirst = functions.stream().filter(f -> f.getName().equals("processWithContextFirst")).findFirst().orElseThrow();
        assertEquals(2, funcFirst.getParameters().size());
        var resultFirst = funcFirst.execute("{\"arg1\":\"hello\",\"arg2\":\"world\"}", context);
        assertTrue(resultFirst.isCompleted());
        assertTrue(resultFirst.getResult().contains("sess-abc"));
        assertTrue(resultFirst.getResult().contains("hello"));
        assertTrue(resultFirst.getResult().contains("world"));

        // Test context in middle
        var funcMiddle = functions.stream().filter(f -> f.getName().equals("processWithContextMiddle")).findFirst().orElseThrow();
        assertEquals(2, funcMiddle.getParameters().size());
        var resultMiddle = funcMiddle.execute("{\"arg1\":\"foo\",\"arg2\":\"bar\"}", context);
        assertTrue(resultMiddle.isCompleted());
        assertTrue(resultMiddle.getResult().contains("sess-abc"));
        assertTrue(resultMiddle.getResult().contains("foo"));
        assertTrue(resultMiddle.getResult().contains("bar"));

        // Test context as last parameter
        var funcLast = functions.stream().filter(f -> f.getName().equals("processWithContextLast")).findFirst().orElseThrow();
        assertEquals(2, funcLast.getParameters().size());
        var resultLast = funcLast.execute("{\"arg1\":\"aaa\",\"arg2\":\"bbb\"}", context);
        assertTrue(resultLast.isCompleted());
        assertTrue(resultLast.getResult().contains("sess-abc"));
        assertTrue(resultLast.getResult().contains("aaa"));
        assertTrue(resultLast.getResult().contains("bbb"));
    }

    @Test
    void testExecutionContextWithNullContext() {
        var functions = Functions.from(service, "getUserInfo");
        var function = functions.getFirst();

        // Execute without context (null)
        var result = function.execute("{\"userId\":\"test-user\"}", null);

        assertTrue(result.isCompleted());
        assertNotNull(result.getResult());
        assertTrue(result.getResult().contains("test-user"));
        assertTrue(result.getResult().contains("context=null"));
    }

    @Test
    void testFunctionWithoutExecutionContext() {
        var functions = Functions.from(service, "simpleMethod");
        assertEquals(1, functions.size());

        var function = functions.getFirst();
        assertEquals(1, function.getParameters().size());

        var context = ExecutionContext.builder().sessionId("test").build();
        var result = function.execute("{\"name\":\"test-name\"}", context);

        assertTrue(result.isCompleted());
        assertTrue(result.getResult().contains("Hello, test-name"));
    }

    @Test
    void testExecutionContextCustomVariables() {
        var functions = Functions.from(service, "useCustomVariable");
        var function = functions.getFirst();

        assertEquals(1, function.getParameters().size());

        var context = ExecutionContext.builder()
                .customVariable("key", "secret-key-123")
                .customVariable("env", "production")
                .build();

        var result = function.execute("{\"action\":\"fetch\"}", context);

        assertTrue(result.isCompleted());
        assertTrue(result.getResult().contains("secret-key-123"));
        assertTrue(result.getResult().contains("production"));
        assertTrue(result.getResult().contains("fetch"));
    }

    /**
     * Test service with ExecutionContext parameters
     */
    public static class ContextAwareService {

        @CoreAiMethod(name = "getUserInfo", description = "Get user information with context")
        public String getUserInfo(
                @CoreAiParameter(name = "userId", description = "The user ID to query") String userId,
                ExecutionContext context) {
            if (context == null) {
                return "userId=" + userId + ", context=null";
            }
            return "userId=" + userId + ", sessionId=" + context.getSessionId() + ", contextUserId=" + context.getUserId();
        }

        @CoreAiMethod(name = "processWithContextFirst", description = "Process with context as first param")
        public String processWithContextFirst(
                ExecutionContext context,
                @CoreAiParameter(name = "arg1", description = "First argument") String arg1,
                @CoreAiParameter(name = "arg2", description = "Second argument") String arg2) {
            return "session=" + context.getSessionId() + ", arg1=" + arg1 + ", arg2=" + arg2;
        }

        @CoreAiMethod(name = "processWithContextMiddle", description = "Process with context in middle")
        public String processWithContextMiddle(
                @CoreAiParameter(name = "arg1", description = "First argument") String arg1,
                ExecutionContext context,
                @CoreAiParameter(name = "arg2", description = "Second argument") String arg2) {
            return "session=" + context.getSessionId() + ", arg1=" + arg1 + ", arg2=" + arg2;
        }

        @CoreAiMethod(name = "processWithContextLast", description = "Process with context as last param")
        public String processWithContextLast(
                @CoreAiParameter(name = "arg1", description = "First argument") String arg1,
                @CoreAiParameter(name = "arg2", description = "Second argument") String arg2,
                ExecutionContext context) {
            return "session=" + context.getSessionId() + ", arg1=" + arg1 + ", arg2=" + arg2;
        }

        @CoreAiMethod(name = "simpleMethod", description = "Simple method without context")
        public String simpleMethod(
                @CoreAiParameter(name = "name", description = "Name to greet") String name) {
            return "Hello, " + name;
        }

        @CoreAiMethod(name = "useCustomVariable", description = "Use custom variables from context")
        public String useCustomVariable(
                @CoreAiParameter(name = "action", description = "Action to perform") String action,
                ExecutionContext context) {
            var key = (String) context.getCustomVariable("key");
            var env = (String) context.getCustomVariable("env");
            return "action=" + action + ", key=" + key + ", env=" + env;
        }
    }
}
