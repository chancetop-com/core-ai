package ai.core.server.skillhub;

import ai.core.api.server.skillhub.SkillHubDetail;
import ai.core.api.server.skillhub.SkillHubLookupRequest;
import ai.core.api.server.skillhub.SkillHubLookupResponse;
import ai.core.api.server.skillhub.SkillHubResourceRequest;
import ai.core.api.server.skillhub.SkillHubResourceResponse;
import ai.core.api.server.skillhub.SkillHubSearchRequest;
import ai.core.api.server.skillhub.SkillHubSearchResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillHubWebServiceImplTest {
    private SkillHubService hubService;
    private SkillHubWebServiceImpl webService;

    @BeforeEach
    void setUp() {
        hubService = mock(SkillHubService.class);
        webService = new SkillHubWebServiceImpl();
        webService.hubService = hubService;
    }

    @Test
    void searchDelegatesWithNullSafeDefaults() {
        var expected = new SkillHubSearchResponse();
        when(hubService.search(null, null, null, null)).thenReturn(expected);
        assertSame(expected, webService.search(null));
        verify(hubService).search(null, null, null, null);

        var request = new SkillHubSearchRequest();
        request.query = "review";
        request.namespace = "stephen";
        request.limit = 5;
        when(hubService.search("review", "stephen", null, 5)).thenReturn(expected);
        assertSame(expected, webService.search(request));
        verify(hubService).search("review", "stephen", null, 5);
    }

    @Test
    void lookupDelegates() {
        var request = new SkillHubLookupRequest();
        request.name = "code-review";
        var expected = new SkillHubLookupResponse();
        when(hubService.lookup("code-review")).thenReturn(expected);
        assertSame(expected, webService.lookup(request));
    }

    @Test
    void showDelegatesPathParams() {
        var expected = new SkillHubDetail();
        when(hubService.show("stephen", "code-review")).thenReturn(expected);
        assertSame(expected, webService.show("stephen", "code-review"));
    }

    @Test
    void resourceDelegatesPathAndQueryParams() {
        var request = new SkillHubResourceRequest();
        request.path = "references/style.md";
        var expected = new SkillHubResourceResponse();
        when(hubService.resource("stephen", "code-review", "references/style.md")).thenReturn(expected);
        assertSame(expected, webService.resource("stephen", "code-review", request));
    }
}
