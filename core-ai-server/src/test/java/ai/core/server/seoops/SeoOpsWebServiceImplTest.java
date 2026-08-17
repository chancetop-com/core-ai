package ai.core.server.seoops;

import ai.core.api.server.seoops.SeoOpsApiModels.CreateMerchantRequest;
import ai.core.api.server.seoops.SeoOpsApiModels.MerchantView;
import ai.core.server.seoops.domain.SeoMerchant;
import ai.core.server.web.auth.AuthContext;
import core.framework.web.WebContext;
import core.framework.web.exception.UnauthorizedException;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author xander
 */
class SeoOpsWebServiceImplTest {
    @Test
    void configExposesOnlyEligibleCopilot() {
        var service = service("user-1");
        when(service.copilotPolicy.eligibleAgentId()).thenReturn(Optional.of("agent-safe"));

        var view = service.config();

        assertEquals(true, view.copilotEnabled);
        assertEquals("agent-safe", view.copilotAgentId);
    }

    @Test
    void mutationDelegatesUsingAuthenticatedActor() {
        var service = service("user-1");
        var request = new CreateMerchantRequest();
        var merchant = new SeoMerchant();
        var expected = new MerchantView();
        when(service.merchantService.createMerchant("user-1", request)).thenReturn(merchant);
        when(service.viewMapper.merchant(merchant)).thenReturn(expected);

        assertEquals(expected, service.createMerchant(request));
        verify(service.merchantService).createMerchant("user-1", request);
    }

    @Test
    void missingActorIsUnauthorized() {
        assertThrows(UnauthorizedException.class, () -> service(null).portfolio());
    }

    private SeoOpsWebServiceImpl service(String actor) {
        var service = new SeoOpsWebServiceImpl();
        service.webContext = mock(WebContext.class);
        when(service.webContext.get(AuthContext.USER_ID_KEY)).thenReturn(actor);
        service.runtimeConfig = new SeoOpsRuntimeConfig(true, "agent-safe");
        service.copilotPolicy = mock(SeoCopilotPolicy.class);
        service.merchantService = mock(SeoMerchantService.class);
        service.taskService = mock(SeoTaskCommandService.class);
        service.queryService = mock(SeoOpsQueryService.class);
        service.viewMapper = mock(SeoOpsViewMapper.class);
        return service;
    }
}
