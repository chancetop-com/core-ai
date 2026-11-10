package ai.core.tool.tools;

import ai.core.agent.ExecutionContext;
import ai.core.decision.DecisionAnswer;
import ai.core.decision.DecisionModelHint;
import ai.core.decision.DecisionProvider;
import ai.core.decision.DecisionQuestion;
import ai.core.decision.DecisionRequest;
import ai.core.decision.DecisionResponse;
import ai.core.decision.DecisionType;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import ai.core.utils.JsonUtil;
import core.framework.util.Strings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends one state plus typed questions to a decision model and returns structured verdicts. The tool
 * never acts on the answers: high-impact actions stay with the agent or the user.
 *
 * @author stephen
 */
public final class DecideTool extends ToolCall {
    public static final String TOOL_NAME = "decide";

    public static final String TOOL_DESC = """
            Ask a decision model (System One) for structured verdicts about one state. Unlike a chat model it
            produces no text: you send a state plus typed questions and get probabilities, a chosen option or
            a score back.

            Question types:
            - noul: a yes/no gate. Returns a 0-1 probability (higher means yes).
            - choice: pick one of the labels in criteria (an object of label -> description).
            - score: rate on an ordered scale. criteria is a list of levels from lowest to highest; the score
              may fall between levels.

            Ask several questions in one call when they share the same state. Use the returned probabilities
            and confidence to gate or rank; do not treat the verdict as an explanation, and never let the
            tool trigger a side effect by itself.
            """;

    private static final int MAX_STATE_LENGTH = 64_000;

    public static String buildDescription(List<DecisionModelHint> models) {
        var description = new StringBuilder(TOOL_DESC);
        if (models == null || models.isEmpty()) {
            description.append("\nNo decision model catalog is available; leave model unset to use the gateway default.");
            return description.toString();
        }
        description.append("\nAvailable decision models (omit model to use the default):");
        for (var model : models) {
            description.append("\n- ").append(model.modelId());
            if (model.displayName() != null && !model.displayName().isBlank()
                    && !model.displayName().equals(model.modelId())) {
                description.append(" (").append(model.displayName()).append(")");
            }
            if (model.providerName() != null && !model.providerName().isBlank()) {
                description.append(" — ").append(model.providerName());
            }
        }
        return description.toString();
    }

    public static Builder builder() {
        return new Builder();
    }

    private DecideTool() {
    }

    @Override
    public ToolCallResult execute(String arguments) {
        return ToolCallResult.failed("decide requires execution context");
    }

    @Override
    public ToolCallResult execute(String arguments, ExecutionContext context) {
        var startTime = System.currentTimeMillis();
        DecisionProvider provider = context.getDecisionProvider();
        if (provider == null) return ToolCallResult.failed("no decision provider configured");
        try {
            var args = parseArguments(arguments);
            var state = getStringValue(args, "state");
            if (Strings.isBlank(state)) return ToolCallResult.failed("state is required");
            if (state.length() > MAX_STATE_LENGTH) {
                return ToolCallResult.failed("state is too long (max " + MAX_STATE_LENGTH + " characters)");
            }
            var questions = questions(getStringValue(args, "questions"));
            var request = new DecisionRequest(getStringValue(args, "model"), state, questions);
            var response = provider.decide(request);
            return ToolCallResult.completed(render(response)).withDuration(System.currentTimeMillis() - startTime);
        } catch (IllegalArgumentException e) {
            return ToolCallResult.failed("Invalid decision request: " + e.getMessage())
                    .withDuration(System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            return ToolCallResult.failed("Decision request failed: " + e.getMessage(), e)
                    .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private List<DecisionQuestion> questions(String json) {
        if (Strings.isBlank(json)) throw new IllegalArgumentException("questions is required");
        Object parsed;
        try {
            parsed = JsonUtil.fromJson(Object.class, json);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("questions must be valid JSON: " + e.getMessage(), e);
        }
        var questions = new ArrayList<DecisionQuestion>();
        if (parsed instanceof List<?> list) {
            for (var item : list) {
                if (!(item instanceof Map<?, ?> map)) throw new IllegalArgumentException("each question must be an object");
                questions.add(question(string(map.get("name")), map));
            }
        } else if (parsed instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if (!(entry.getValue() instanceof Map<?, ?> item)) {
                    throw new IllegalArgumentException("question '" + entry.getKey() + "' must be an object");
                }
                questions.add(question(String.valueOf(entry.getKey()), item));
            }
        } else {
            throw new IllegalArgumentException("questions must be a JSON array or object");
        }
        if (questions.isEmpty()) throw new IllegalArgumentException("questions is required");
        return questions;
    }

    private DecisionQuestion question(String name, Map<?, ?> value) {
        if (Strings.isBlank(name)) throw new IllegalArgumentException("question name is required");
        var type = DecisionType.from(string(value.get("type")));
        if (type == null) throw new IllegalArgumentException("unsupported question type for '" + name + "': " + value.get("type"));
        var instructions = string(value.get("instructions"));
        var criteria = value.get("criteria");
        if (type == DecisionType.SCORE) {
            var levels = levels(criteria);
            return new DecisionQuestion(name, type, instructions, null, levels);
        }
        if (type == DecisionType.NOUL && criteria == null) {
            return new DecisionQuestion(name, type, instructions, null, null);
        }
        var options = options(criteria);
        return new DecisionQuestion(name, type, instructions, options, null);
    }

    private List<String> levels(Object value) {
        var parsed = parseEmbeddedJson(value);
        if (!(parsed instanceof List<?> list)) {
            throw new IllegalArgumentException("score criteria must be an ordered JSON array of levels");
        }
        var levels = new ArrayList<String>(list.size());
        for (var level : list) {
            if (level instanceof Map<?, ?> map && map.get("label") != null) {
                levels.add(String.valueOf(map.get("label")));
            } else if (level != null) {
                levels.add(level instanceof String text ? text : JsonUtil.toJson(level));
            }
        }
        if (levels.isEmpty()) throw new IllegalArgumentException("score criteria must contain at least one level");
        return levels;
    }

    private Map<String, String> options(Object value) {
        var parsed = parseEmbeddedJson(value);
        if (!(parsed instanceof Map<?, ?> map) || map.isEmpty()) {
            throw new IllegalArgumentException("noul/choice criteria must be a JSON object of label -> description");
        }
        var options = new LinkedHashMap<String, String>();
        for (var entry : map.entrySet()) {
            var option = String.valueOf(entry.getKey());
            var description = entry.getValue() == null ? null : String.valueOf(entry.getValue());
            options.put(option, description);
        }
        return options;
    }

    private Object parseEmbeddedJson(Object value) {
        if (!(value instanceof String text)) return value;
        var trimmed = text.trim();
        if (!(trimmed.startsWith("{") && trimmed.endsWith("}") || trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            return value;
        }
        try {
            return JsonUtil.fromJson(Object.class, trimmed);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("criteria is not valid JSON: " + e.getMessage(), e);
        }
    }

    private String string(Object value) {
        return value instanceof String text ? text : null;
    }

    private String render(DecisionResponse response) {
        var body = new LinkedHashMap<String, Object>();
        body.put("model", response.model());
        var answers = new LinkedHashMap<String, Object>();
        for (var entry : response.answers().entrySet()) answers.put(entry.getKey(), answer(entry.getValue()));
        body.put("answers", answers);
        if (response.usage() != null) {
            body.put("usage", Map.of("input_tokens", response.usage().inputTokens(),
                    "output_tokens", response.usage().outputTokens()));
        }
        return JsonUtil.toJson(body);
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

    @Override
    public long getTimeoutMs() {
        return 60_000L;
    }

    @Override
    public boolean isConcurrencySafe(String arguments) {
        return true;
    }

    public static class Builder extends ToolCall.Builder<Builder, DecideTool> {
        private boolean customDescriptionSet;

        @Override
        public Builder description(String description) {
            customDescriptionSet = true;
            super.description(description);
            return this;
        }

        @Override
        protected Builder self() {
            return this;
        }

        public DecideTool build() {
            this.name(TOOL_NAME);
            if (!customDescriptionSet) super.description(TOOL_DESC);
            this.parameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "state", "The thing being judged: text, or a JSON object/array already serialized as text").required(),
                    ToolCallParameters.ParamSpec.of(String.class, "questions", "JSON array (or object keyed by name) of questions: {name, type: noul|choice|score, instructions, criteria}. noul/choice criteria is an object of label -> description; score criteria is an ordered array of levels from lowest to highest").required(),
                    ToolCallParameters.ParamSpec.of(String.class, "model", "Decision model id (uses the gateway default when omitted); do not guess")
            ));
            var tool = new DecideTool();
            build(tool);
            return tool;
        }
    }
}
