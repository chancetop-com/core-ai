package ai.core.benchmark.evaluator.handle;

import ai.core.agent.Agent;
import ai.core.benchmark.domain.BFCLItem;
import ai.core.llm.domain.Function;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Tool;
import ai.core.llm.domain.ToolType;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;

/**
 * author: lim chen
 * date: 2025/12/22
 * description: base function call
 */
public class BFCLAgentFCHandle extends BFCLAgentHandle {

    @Override
    public List<Tool> completeTools(BFCLItem item) {
        ObjectMapper mapper = new ObjectMapper();
        var functions = item.function;
        return functions.stream().map(function -> {
            var tool = new Tool();
            tool.type = ToolType.FUNCTION;
            try {
                function.parameters.type = Objects.equals(function.parameters.type, "dict") ? "object" : function.parameters.type;
                var funcJson = mapper.writeValueAsString(function);
                tool.function = JsonUtil.fromJson(Function.class, funcJson);
                return tool;
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }

        }).toList();
    }

    @Override
    public List<Message> completeMessages(Agent agent, BFCLItem item) {
        var user = Message.of(RoleType.USER, item.question.getFirst().getFirst().content);
        return List.of(user);
    }

}
