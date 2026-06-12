package ai.core.server.workflow;

import ai.core.server.workflow.engine.WorkflowGraph;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class WorkflowGraphParserTest {
    @Test
    void parsesNodesAndEdgesAndExcludesNote() {
        String json = """
            {
              "format": "core-ai-workflow/v1",
              "nodes": [
                {"id": "start", "type": "START", "name": "In", "config": {}},
                {"id": "note1", "type": "NOTE", "name": "a sticky note"},
                {"id": "end", "type": "END", "position": {"x": 80, "y": 120}}
              ],
              "edges": [
                {"id": "e0", "source": "start", "target": "end", "label": "go"}
              ]
            }
            """;

        WorkflowGraph graph = WorkflowGraphParser.parse(json);

        assertEquals(2, graph.nodes().size());   // NOTE excluded from the executable graph
        assertNotNull(graph.node("start"));
        assertNotNull(graph.node("end"));
        assertNull(graph.node("note1"));
        assertEquals("START", graph.node("start").type());
        assertEquals("In", graph.node("start").name());
        assertEquals("end", graph.node("end").name());
        assertEquals(1, graph.edges().size());
        assertEquals("start", graph.edges().get(0).source());
        assertEquals("end", graph.edges().get(0).target());
    }

    @Test
    void toleratesMissingEdgesArray() {
        WorkflowGraph graph = WorkflowGraphParser.parse("{\"nodes\": [{\"id\": \"only\", \"type\": \"START\"}]}");

        assertEquals(1, graph.nodes().size());
        assertEquals(0, graph.edges().size());
    }
}
