package ai.core.server.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolRefTest {
    @Test
    void infersServiceApiRefTypes() {
        assertApi("api-app:core");
        assertApi("api-service:core:UserService");
        assertApi("api-operation:core:UserService:getUser");
        assertApi("builtin-service-api");
    }

    private void assertApi(String id) {
        var ref = new ToolRef();
        ref.id = id;
        ref.inferTypeFromId();
        assertEquals(ToolSourceType.API, ref.type);
    }
}
