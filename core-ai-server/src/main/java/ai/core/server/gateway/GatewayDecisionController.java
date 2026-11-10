package ai.core.server.gateway;

import ai.core.decision.DecisionAnswer;
import ai.core.decision.DecisionException;
import ai.core.decision.DecisionQuestion;
import ai.core.decision.DecisionRequest;
import ai.core.decision.DecisionResponse;
import ai.core.decision.DecisionType;
import ai.core.server.hub.HubCallAuditService;
import ai.core.server.rbac.PermissionsBypass;
import com.fasterxml.jackson.core.JsonProcessingException;
import core.framework.http.ContentType;
import core.framework.inject.Inject;
import core.framework.util.StopWatch;
import core.framework.web.Request;
import core.framework.web.Response;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.TooManyRequestsException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * System One shaped decision API backed by the gateway routing layer. External apps, the CLI and the
 * desktop engine all use the signed-in user's credential; routing, provider profiles, normalization and
 * errors stay server-side.
 *
 * @author stephen
 */
@PermissionsBypass
public class GatewayDecisionController {
    @Inject
    GatewayDecisionProvider gatewayDecisionProvider;
    @Inject
    GatewayCallAuditService gatewayCallAudit;

    public Response decide(Request request) {
        var decisionRequest = decisionRequest(body(request));
        var auditId = gatewayCallAudit.begin(HubCallAuditService.KIND_DECISION, "decide", "decision",
                decisionRequest.model(), gatewayCallAudit.decisionArguments(decisionRequest), null);
        var watch = new StopWatch();
        try {
            var response = decide(decisionRequest);
            gatewayCallAudit.finish(auditId, watch, GatewayCallAuditService.Result.completed(
                    gatewayCallAudit.decisionOutputBytes(response), null,
                    gatewayCallAudit.inputTokens(response.usage()), gatewayCallAudit.outputTokens(response.usage())));
            return json(response);
        } catch (RuntimeException e) {
            gatewayCallAudit.finish(auditId, watch,
                    GatewayCallAuditService.Result.failure(gatewayCallAudit.failureStatus(e), e.getMessage()));
            throw e;
        }
    }

    DecisionResponse decide(byte[] body) {
        return decide(decisionRequest(body));
    }

    DecisionResponse decide(DecisionRequest request) {
        try {
            return gatewayDecisionProvider.decide(request);
        } catch (DecisionException e) {
            throw map(e);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage(), "BAD_REQUEST", e);
        }
    }

    DecisionRequest decisionRequest(byte[] body) {
        Map<String, Object> value;
        try {
            value = GatewayJson.MAPPER.readValue(body, GatewaySupport.MAP_TYPE);
        } catch (Exception e) {
            throw new BadRequestException("invalid JSON body: " + e.getMessage(), "BAD_REQUEST", e);
        }
        return new DecisionRequest(string(value.get("model")), state(value.get("state")), questions(value.get("questions")));
    }

    private byte[] body(Request request) {
        return request.body().orElseThrow(() -> new BadRequestException("body is required"));
    }

    private List<DecisionQuestion> questions(Object value) {
        if (!(value instanceof Map<?, ?> questions) || questions.isEmpty()) {
            throw new BadRequestException("decision questions are required");
        }
        var result = new ArrayList<DecisionQuestion>(questions.size());
        for (var entry : questions.entrySet()) {
            var name = String.valueOf(entry.getKey());
            if (!(entry.getValue() instanceof Map<?, ?> question)) {
                throw new BadRequestException("decision question must be an object: " + name);
            }
            result.add(question(name, question));
        }
        return result;
    }

    private DecisionQuestion question(String name, Map<?, ?> value) {
        var type = DecisionType.from(string(value.get("type")));
        if (type == null) throw new BadRequestException("unsupported decision question type: " + value.get("type"));
        var instructions = string(value.get("instructions"));
        var criteria = value.get("criteria");
        if (type == DecisionType.SCORE) {
            if (!(criteria instanceof List<?> list) || list.isEmpty()) {
                throw new BadRequestException("score question requires an ordered criteria array: " + name);
            }
            var levels = new ArrayList<String>(list.size());
            for (var level : list) {
                if (level != null) levels.add(level instanceof String text ? text : GatewaySupport.jsonText(level));
            }
            return new DecisionQuestion(name, type, instructions, null, levels);
        }
        if (type == DecisionType.NOUL && criteria == null) {
            return new DecisionQuestion(name, type, instructions, null, null);
        }
        if (!(criteria instanceof Map<?, ?> map) || map.isEmpty()) {
            throw new BadRequestException((type == DecisionType.CHOICE ? "choice" : "noul")
                    + " question requires a criteria object: " + name);
        }
        var options = new LinkedHashMap<String, String>();
        for (var entry : map.entrySet()) {
            options.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }
        return new DecisionQuestion(name, type, instructions, options, null);
    }

    private String state(Object value) {
        if (value == null) throw new BadRequestException("decision state is required");
        if (value instanceof String text) return text;
        return GatewaySupport.jsonText(value);
    }

    private String string(Object value) {
        return value instanceof String text ? text : null;
    }

    private Response json(DecisionResponse response) {
        var body = new LinkedHashMap<String, Object>();
        body.put("model", response.model());
        var answers = new LinkedHashMap<String, Object>();
        for (var entry : response.answers().entrySet()) answers.put(entry.getKey(), answer(entry.getValue()));
        body.put("answers", answers);
        if (response.usage() != null) {
            body.put("usage", Map.of("input_tokens", response.usage().inputTokens(),
                    "output_tokens", response.usage().outputTokens()));
        }
        try {
            return Response.bytes(GatewayJson.MAPPER.writeValueAsBytes(body)).contentType(ContentType.APPLICATION_JSON);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("failed to serialize gateway decision response", e);
        }
    }

    private Map<String, Object> answer(DecisionAnswer answer) {
        var value = new LinkedHashMap<String, Object>();
        if (answer.type() == null) return value;
        value.put("type", answer.type().value());
        if (answer.type() == DecisionType.NOUL) {
            value.put("noul", answer.probability());
        } else if (answer.type() == DecisionType.CHOICE) {
            value.put("choice", answer.choice());
            if (answer.probabilities() != null) value.put("probabilities", answer.probabilities());
            if (answer.confidence() != null) value.put("confidence", answer.confidence());
        } else if (answer.type() == DecisionType.SCORE) {
            value.put("score", answer.score());
            if (answer.legend() != null) value.put("legend", answer.legend());
            if (answer.probabilities() != null) value.put("probabilities", answer.probabilities());
            if (answer.confidence() != null) value.put("confidence", answer.confidence());
        }
        return value;
    }

    private RuntimeException map(DecisionException e) {
        if (e.statusCode() == 429) return new TooManyRequestsException(e.getMessage());
        if (e.statusCode() >= 400 && e.statusCode() < 500) {
            return new BadRequestException(e.getMessage(), e.code() == null ? "BAD_REQUEST" : e.code());
        }
        return new RuntimeException(e.getMessage(), e);
    }
}
