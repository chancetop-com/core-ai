package ai.core.cli.hub.apitool;

import ai.core.api.server.apitoolhub.ApiToolHubLookupResponse;
import ai.core.api.server.apitoolhub.ApiToolHubOperationSummary;
import ai.core.cli.hub.HubCliError;
import ai.core.cli.hub.HubExitCodes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiToolNameParserTest {

    @Test
    void parsesThreePartQualifiedName() {
        assertArrayEquals(new String[]{"order-service", "refund", "create"},
                ApiToolNameParser.parseQualified("order-service/refund/create"));
    }

    @Test
    void bareNameWithoutSlashMeansLookup() {
        assertNull(ApiToolNameParser.parseQualified("order_service_refund_create"));
    }

    @Test
    void blankOrWrongSegmentCountIsUsageError() {
        assertThrows(HubCliError.class, () -> ApiToolNameParser.parseQualified(""));
        assertThrows(HubCliError.class, () -> ApiToolNameParser.parseQualified("   "));
        var tooFew = assertThrows(HubCliError.class, () -> ApiToolNameParser.parseQualified("a/b"));
        assertEquals(HubExitCodes.USAGE, tooFew.exitCode);
        assertThrows(HubCliError.class, () -> ApiToolNameParser.parseQualified("a/b/c/d"));
        assertThrows(HubCliError.class, () -> ApiToolNameParser.parseQualified("//c"));
    }

    @Test
    void resolveFallsBackToServerLookupForBareNames() {
        var client = mock(ApiToolHubClient.class);
        var response = new ApiToolHubLookupResponse();
        var summary = new ApiToolHubOperationSummary();
        summary.app = "order-service";
        summary.service = "refund";
        summary.name = "create";
        response.operation = summary;
        when(client.lookup("order_service_refund_create")).thenReturn(response);

        assertArrayEquals(new String[]{"order-service", "refund", "create"},
                ApiToolNameParser.resolve(client, "order_service_refund_create"));
    }

    @Test
    void resolveFailsWithNotFoundWhenLookupMisses() {
        var client = mock(ApiToolHubClient.class);
        var response = new ApiToolHubLookupResponse();
        when(client.lookup("order_service_refund_delete")).thenReturn(response);

        var error = assertThrows(HubCliError.class,
                () -> ApiToolNameParser.resolve(client, "order_service_refund_delete"));
        assertEquals(HubExitCodes.NOT_FOUND, error.exitCode);
    }
}
