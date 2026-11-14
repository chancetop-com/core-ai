package ai.core.decision;

import ai.core.internal.http.PatchedHTTPClientBuilder;
import ai.core.utils.JsonUtil;
import core.framework.http.ContentType;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * System One decision API adapter. Microsoft-Decision-1, Jev and the OpenRouter Decisions API share the
 * same {@code state + questions -> answers} wire contract; only the full request path and the credential
 * header differ, so one adapter plus a per-vendor profile covers all of them.
 *
 * @author stephen
 */
public class SystemOneDecisionProvider implements DecisionProvider {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_ERROR_BODY = 2000;

    private final String baseUrl;
    private final String endpoint;
    private final String credential;
    private final String credentialHeader;
    private final Map<String, String> extraHeaders;
    private final HTTPClient client;

    public SystemOneDecisionProvider(String baseUrl, String credential, String credentialHeader, String path) {
        this(baseUrl, credential, credentialHeader, path, Map.of());
    }

    protected SystemOneDecisionProvider(String baseUrl, String credential, String credentialHeader, String path,
                                        Map<String, String> extraHeaders) {
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("decision provider base URL is required");
        if (path == null || path.isBlank()) throw new IllegalArgumentException("decision provider path is required");
        this.baseUrl = stripTrailingSlashes(baseUrl.trim());
        this.endpoint = this.baseUrl + normalizePath(path.trim());
        this.credential = credential;
        this.credentialHeader = credentialHeader;
        this.extraHeaders = extraHeaders == null ? Map.of() : Map.copyOf(extraHeaders);
        this.client = new PatchedHTTPClientBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .timeout(REQUEST_TIMEOUT)
                .trustAll()
                .build();
    }

    protected String baseUrl() {
        return baseUrl;
    }

    protected String credential() {
        return credential;
    }

    @Override
    public DecisionResponse decide(DecisionRequest request) {
        return decide(request, DecisionOptions.none());
    }

    @Override
    public DecisionResponse decide(DecisionRequest request, DecisionOptions options) {
        var httpRequest = new HTTPRequest(HTTPMethod.POST, endpoint);
        httpRequest.headers.put("Content-Type", ContentType.APPLICATION_JSON.toString());
        applyOptions(httpRequest, options);
        setHeaders(httpRequest);
        httpRequest.body(JsonUtil.toJson(body(request)).getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);

        var response = client.execute(httpRequest);
        if (response.statusCode < 200 || response.statusCode >= 300) {
            throw error(response.statusCode, response.text());
        }
        return parseResponse(response.text());
    }

    private void applyOptions(HTTPRequest request, DecisionOptions options) {
        if (options == null) return;
        if (options.timeout() != null) request.timeout = options.timeout();
        if (options.connectTimeout() != null) request.connectTimeout = options.connectTimeout();
    }

    @Override
    public String name() {
        return "systemone";
    }

    /**
     * Hook for gateway clients that add routing headers (client type, session id) to every call.
     */
    protected void setHeaders(HTTPRequest request) {
        extraHeaders.forEach((name, value) -> {
            if (value != null) request.headers.put(name, value);
        });
        if (credential == null || credential.isBlank()) return;
        if (credentialHeader == null || credentialHeader.isBlank()) return;
        request.headers.put(credentialHeader, credentialValue());
    }

    private String credentialValue() {
        if ("Authorization".equalsIgnoreCase(credentialHeader) && !credential.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return "Bearer " + credential;
        }
        return credential;
    }

    private Map<String, Object> body(DecisionRequest request) {
        var body = new LinkedHashMap<String, Object>();
        if (request.model() != null && !request.model().isBlank()) body.put("model", request.model().trim());
        body.put("state", stateValue(request.state()));
        body.put("questions", questions(request.questions()));
        return body;
    }

    private Map<String, Object> questions(List<DecisionQuestion> questions) {
        var value = new LinkedHashMap<String, Object>();
        for (var question : questions) {
            if (value.containsKey(question.name())) {
                throw new IllegalArgumentException("duplicate decision question name: " + question.name());
            }
            value.put(question.name(), question(question));
        }
        return value;
    }

    private Map<String, Object> question(DecisionQuestion question) {
        var value = new LinkedHashMap<String, Object>();
        value.put("type", question.type().value());
        if (question.instructions() != null && !question.instructions().isBlank()) {
            value.put("instructions", question.instructions());
        }
        if (question.type() == DecisionType.SCORE) {
            value.put("criteria", question.levels());
        } else if (question.criteria() != null && !question.criteria().isEmpty()) {
            value.put("criteria", question.criteria());
        }
        return value;
    }

    /**
     * The API accepts a JSON value as well as a string. Callers hand us the raw text, so a state that is
     * already a JSON object/array is passed as that value instead of an escaped string.
     */
    private Object stateValue(String state) {
        var trimmed = state.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                return JsonUtil.toMap(trimmed);
            } catch (RuntimeException e) {
                return state;
            }
        }
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            try {
                return JsonUtil.fromJson(List.class, trimmed);
            } catch (RuntimeException e) {
                return state;
            }
        }
        return state;
    }

    @SuppressWarnings("unchecked")
    private DecisionResponse parseResponse(String json) {
        Map<String, Object> response;
        try {
            response = JsonUtil.fromJson(Map.class, json);
        } catch (RuntimeException e) {
            throw new DecisionException(200, null, "decision response is not JSON: " + truncate(json), e);
        }
        var answers = new LinkedHashMap<String, DecisionAnswer>();
        if (response.get("answers") instanceof Map<?, ?> rawAnswers) {
            for (var entry : rawAnswers.entrySet()) {
                if (!(entry.getValue() instanceof Map<?, ?> rawAnswer)) continue;
                var name = String.valueOf(entry.getKey());
                answers.put(name, answer(name, (Map<String, Object>) rawAnswer));
            }
        }
        return new DecisionResponse(answers, string(response.get("model")), usage(response.get("usage")));
    }

    private DecisionAnswer answer(String name, Map<String, Object> answer) {
        var type = DecisionType.from(string(answer.get("type")));
        if (type == null) type = inferType(answer);
        Double probability = null;
        String choice = null;
        Double score = null;
        Map<String, Double> probabilities = numbers(answer.get("probabilities"));
        Double confidence = number(answer.get("confidence"));
        Map<String, String> legend = strings(answer.get("legend"));
        if (type == DecisionType.NOUL) {
            probability = number(answer.get("noul"));
            if (probability == null) probability = number(answer.get("probability"));
        } else if (type == DecisionType.CHOICE) {
            choice = string(answer.get("choice"));
            if (choice != null && probabilities != null) probability = probabilities.get(choice);
        } else if (type == DecisionType.SCORE) {
            score = number(answer.get("score"));
        }
        return new DecisionAnswer(name, type, probability, choice, score, probabilities, confidence, legend);
    }

    private DecisionType inferType(Map<String, Object> answer) {
        if (answer.containsKey("noul") || answer.containsKey("probability")) return DecisionType.NOUL;
        if (answer.containsKey("choice")) return DecisionType.CHOICE;
        if (answer.containsKey("score")) return DecisionType.SCORE;
        return null;
    }

    private DecisionUsage usage(Object value) {
        if (!(value instanceof Map<?, ?> usage)) return null;
        return new DecisionUsage(intValue(usage.get("input_tokens")), intValue(usage.get("output_tokens")));
    }

    private int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private Double number(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.valueOf(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Map<String, Double> numbers(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) return null;
        var result = new LinkedHashMap<String, Double>();
        for (var entry : map.entrySet()) {
            var number = number(entry.getValue());
            if (number != null) result.put(String.valueOf(entry.getKey()), number);
        }
        return result.isEmpty() ? null : result;
    }

    private Map<String, String> strings(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) return null;
        var result = new LinkedHashMap<String, String>();
        for (var entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }
        return result;
    }

    private String string(Object value) {
        return value instanceof String text ? text : null;
    }

    @SuppressWarnings("unchecked")
    private DecisionException error(int statusCode, String body) {
        String code = null;
        String message = truncate(body);
        try {
            var error = JsonUtil.fromJson(Map.class, body);
            Object detail = error.get("error");
            if (detail instanceof Map<?, ?> detailMap) {
                if (detailMap.get("code") instanceof String value) code = value;
                if (detailMap.get("message") instanceof String value) message = value;
            } else if (error.get("message") instanceof String value) {
                message = value;
            }
        } catch (RuntimeException ignored) {
            // non-JSON error body: keep the raw text
        }
        return new DecisionException(statusCode, code,
                "decision request failed: HTTP " + statusCode + (code == null ? "" : " [" + code + "]") + ": " + message);
    }

    private String truncate(String value) {
        if (value == null) return "";
        return value.length() <= MAX_ERROR_BODY ? value : value.substring(0, MAX_ERROR_BODY);
    }

    private String stripTrailingSlashes(String value) {
        var result = value;
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private String normalizePath(String value) {
        return value.startsWith("/") ? value : "/" + value;
    }
}
