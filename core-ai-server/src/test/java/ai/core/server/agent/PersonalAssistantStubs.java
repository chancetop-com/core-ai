package ai.core.server.agent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Entry points resolve the assistant template through {@link PersonalAssistantService}; tests that
 * exercise other agents only need that redirection to be a no-op.
 */
public final class PersonalAssistantStubs {
    public static PersonalAssistantService passThrough() {
        var service = mock(PersonalAssistantService.class);
        when(service.resolve(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        return service;
    }

    private PersonalAssistantStubs() {
    }
}
