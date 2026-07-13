package ai.core.server.dataset.tool;

import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.dataset.DatasetService;
import ai.core.tool.ToolCall;
import ai.core.tool.registry.ToolProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Provides dataset CRUD tools scoped to the given {@link DatasetAccessRegistry}.
 *
 * @author Lim Chen
 */
public class DatasetToolProvider implements ToolProvider {
    private final Map<String, ToolCall> tools;

    public DatasetToolProvider(DatasetService datasetService, DatasetRecordService datasetRecordService,
                                DatasetAccessRegistry registry, String agentId, String runId) {
        var list = new ArrayList<ToolCall>();
        list.add(QueryDatasetRecordsTool.create(datasetService, datasetRecordService, registry));
        if (registry.hasAnyWrite()) {
            list.add(InsertDatasetRecordTool.create(agentId, runId, datasetService, datasetRecordService, registry));
            list.add(UpdateDatasetRecordTool.create(datasetService, datasetRecordService, registry));
        }
        if (registry.hasAnyFull()) {
            list.add(DeleteDatasetRecordTool.create(datasetService, datasetRecordService, registry));
        }
        var map = new LinkedHashMap<String, ToolCall>();
        for (var tc : list) {
            map.put(tc.getName(), tc);
        }
        this.tools = Map.copyOf(map);
    }

    @Override
    public String id() {
        return DATASET;
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public RefreshPolicy refreshPolicy() {
        return RefreshPolicy.ONCE;
    }

    @Override
    public Map<String, ToolCall> provide() {
        return tools;
    }
}
