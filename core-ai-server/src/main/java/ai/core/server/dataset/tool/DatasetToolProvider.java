package ai.core.server.dataset.tool;

import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.dataset.DatasetService;
import ai.core.server.domain.DatasetType;
import ai.core.tool.ToolCall;
import ai.core.tool.registry.ToolProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Provides dataset tools scoped to the given {@link DatasetAccessRegistry}.
 * SESSION datasets only expose get_session_state/set_session_state/update_session_state;
 * GENERAL datasets only expose the record CRUD tools.
 *
 * @author Lim Chen
 */
public class DatasetToolProvider implements ToolProvider {
    private final Map<String, ToolCall> tools;

    public DatasetToolProvider(DatasetService datasetService, DatasetRecordService datasetRecordService,
                                DatasetAccessRegistry registry, String agentId, String runId) {
        var hasGeneral = registry.allowedDatasets().keySet().stream()
                .anyMatch(id -> DatasetService.resolveType(datasetService.get(id)) == DatasetType.GENERAL);
        var hasSession = registry.allowedDatasets().keySet().stream()
                .anyMatch(id -> DatasetService.resolveType(datasetService.get(id)) == DatasetType.SESSION);
        var list = new ArrayList<ToolCall>();
        if (hasGeneral) {
            list.add(QueryDatasetRecordsTool.create(datasetService, datasetRecordService, registry));
            if (registry.hasAnyWrite()) {
                list.add(InsertDatasetRecordTool.create(agentId, runId, datasetService, datasetRecordService, registry));
                list.add(UpdateDatasetRecordTool.create(datasetService, datasetRecordService, registry));
            }
            if (registry.hasAnyFull()) {
                list.add(DeleteDatasetRecordTool.create(datasetService, datasetRecordService, registry));
            }
        }
        if (hasSession) {
            list.add(GetSessionStateTool.create(datasetService, datasetRecordService, registry));
            if (registry.hasAnyWrite()) {
                list.add(SetSessionStateTool.create(agentId, datasetService, datasetRecordService, registry));
                list.add(UpdateSessionStateTool.create(agentId, datasetService, datasetRecordService, registry));
            }
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
