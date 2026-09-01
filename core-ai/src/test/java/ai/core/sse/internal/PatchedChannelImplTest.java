package ai.core.sse.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PatchedChannelImplTest {
    @Test
    void frameIncludesIdLineWhenPresent() {
        assertEquals("id: 7\nevent: output\ndata: abc\n\n", PatchedChannelImpl.frame("7", "output", "abc"));
    }

    @Test
    void frameOmitsIdLineWhenIdIsNull() {
        assertEquals("event: output\ndata: abc\n\n", PatchedChannelImpl.frame(null, "output", "abc"));
    }

    @Test
    void frameOmitsIdLineWhenIdIsBlank() {
        assertEquals("event: output\ndata: abc\n\n", PatchedChannelImpl.frame("   ", "output", "abc"));
    }
}
