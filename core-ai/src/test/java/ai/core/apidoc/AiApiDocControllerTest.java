package ai.core.apidoc;

import core.framework.http.ContentType;
import core.framework.internal.web.http.IPAccessControl;
import core.framework.web.Request;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiApiDocControllerTest {
    @Test
    void servesDefinition() throws Exception {
        var accessControl = mock(IPAccessControl.class);
        var request = mock(Request.class);
        when(request.clientIP()).thenReturn("127.0.0.1");
        var controller = new AiApiDocController(accessControl, Set.of(AiApiDocBuilderTest.OrderService.class), Set.of(), "Order app", true, false);

        controller.initialize();
        var response = controller.execute(request);

        assertNotNull(response);
        assertEquals(ContentType.APPLICATION_JSON, response.contentType().orElseThrow());
        verify(accessControl).validate("127.0.0.1");
    }

    @Test
    void failsFastWhenRequiredDescriptionsAreMissing() {
        var controller = new AiApiDocController(mock(IPAccessControl.class), Set.of(AiApiDocBuilderTest.InventoryService.class), Set.of(), null, false, true);

        assertThrows(Error.class, controller::initialize);
    }
}
