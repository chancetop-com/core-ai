package ai.core.api.server;

import ai.core.api.server.agent.ActivateAgentQualityPolicyRequest;
import ai.core.api.server.agent.AgentQualityPolicyListResponse;
import ai.core.api.server.agent.AgentQualityPolicyView;
import ai.core.api.server.agent.AgentQualityRuleListResponse;
import ai.core.api.server.agent.AgentQualityRuleTemplateListResponse;
import ai.core.api.server.agent.AgentQualityRuleView;
import ai.core.api.server.agent.CreateAgentQualityRuleRequest;
import ai.core.api.server.agent.TestAgentQualityRuleRequest;
import ai.core.api.server.agent.TestAgentQualityRuleResponse;
import ai.core.api.server.agent.UpdateAgentQualityRuleRequest;
import core.framework.api.http.HTTPStatus;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.PUT;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;
import core.framework.api.web.service.ResponseStatus;

/**
 * Publish-policy administration. Every method requires a human platform admin at the service layer;
 * the route-level RBAC annotation is not sufficient because API users bypass action checks.
 *
 * @author core-ai
 */
public interface AgentQualityAdminWebService {
    @GET
    @Path("/api/admin/agent-quality/rule-templates")
    AgentQualityRuleTemplateListResponse templates();

    @GET
    @Path("/api/admin/agent-quality/rules")
    AgentQualityRuleListResponse rules();

    @POST
    @Path("/api/admin/agent-quality/rules")
    @ResponseStatus(HTTPStatus.CREATED)
    AgentQualityRuleView createRule(CreateAgentQualityRuleRequest request);

    @PUT
    @Path("/api/admin/agent-quality/rules/:id")
    AgentQualityRuleView updateRule(@PathParam("id") String id, UpdateAgentQualityRuleRequest request);

    @POST
    @Path("/api/admin/agent-quality/rules/:id/test")
    TestAgentQualityRuleResponse testRule(@PathParam("id") String id, TestAgentQualityRuleRequest request);

    @POST
    @Path("/api/admin/agent-quality/rules/:id/retire")
    AgentQualityRuleView retireRule(@PathParam("id") String id);

    @GET
    @Path("/api/admin/agent-quality/policies")
    AgentQualityPolicyListResponse policies();

    @POST
    @Path("/api/admin/agent-quality/policies/activate")
    AgentQualityPolicyView activatePolicy(ActivateAgentQualityPolicyRequest request);
}
