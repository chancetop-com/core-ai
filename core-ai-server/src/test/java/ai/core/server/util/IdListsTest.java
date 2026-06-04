package ai.core.server.util;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class IdListsTest {
    @Test
    void cleanRemovesNullBlankAndDuplicates() {
        assertEquals(List.of("a", "b"), IdLists.clean(Arrays.asList("a", null, "", " ", " b ", "a")));
    }

    @Test
    void cleanOrNullReturnsNullWhenNoValidIdsRemain() {
        assertNull(IdLists.cleanOrNull(Arrays.asList(null, "", " ")));
    }
}
