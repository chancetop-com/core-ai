package ai.core.server.seoops;

import ai.core.api.server.seoops.SeoOpsApiModels;
import ai.core.api.server.seoops.SeoOpsWebService;
import core.framework.api.json.Property;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.QueryParam;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeoOpsApiContractTest {
    @Test
    void exposesTheControlPlaneEndpointSet() {
        var paths = Arrays.stream(SeoOpsWebService.class.getDeclaredMethods())
            .collect(Collectors.toMap(method -> method.getName(), method -> method.getAnnotation(Path.class).value()));

        assertEquals(15, paths.size());
        assertEquals("/api/seo-ops/config", paths.get("config"));
        assertEquals("/api/seo-ops/portfolio", paths.get("portfolio"));
        assertEquals("/api/seo-ops/tasks/:id/approval-decisions", paths.get("approvalDecision"));
    }

    @Test
    void keepsSnakeCaseAtTheWireBoundary() throws Exception {
        var merchantId = SeoOpsApiModels.CreateTaskRequest.class.getField("merchantId").getAnnotation(Property.class);
        var evidenceState = SeoOpsApiModels.PageRequest.class.getField("evidenceState").getAnnotation(QueryParam.class);

        assertEquals("merchant_id", merchantId.name());
        assertEquals("evidence_state", evidenceState.name());
    }

    @Test
    void runtimeConfigDefaultsToDisabledAndTrimsAgentId() {
        var disabled = SeoOpsRuntimeConfig.from(Map.of());
        var enabled = SeoOpsRuntimeConfig.from(Map.of(
            "sys.seoops.enabled", "true",
            "sys.seoops.copilot.agent-id", " agent-safe "));

        assertFalse(disabled.enabled());
        assertEquals(null, disabled.copilotAgentId());
        assertTrue(enabled.enabled());
        assertEquals("agent-safe", enabled.copilotAgentId());
    }
}
