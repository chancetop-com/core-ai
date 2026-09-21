package ai.core.cli.hub.dataset;

import ai.core.api.server.hub.HubDatasetView;
import ai.core.cli.hub.HubCliError;
import ai.core.cli.hub.HubExitCodes;

import java.util.List;

/**
 * Picks the dataset behind a caller reference out of one session's bindings, with the same rules the server applies to
 * a tool call: the id wins, a name works only when it is unique inside the session, and an ambiguous name fails closed
 * instead of silently picking one.
 *
 * @author stephen
 */
class DatasetRefResolver {
    HubDatasetView resolve(List<HubDatasetView> datasets, String datasetRef) {
        for (var view : datasets) {
            if (datasetRef.equals(view.datasetId)) return view;
        }
        var matches = datasets.stream().filter(view -> datasetRef.equals(view.name)).toList();
        if (matches.size() == 1) return matches.get(0);
        if (matches.isEmpty()) {
            throw new HubCliError(HubExitCodes.NOT_FOUND, "dataset not found in this session: " + datasetRef);
        }
        var candidates = matches.stream().map(view -> view.datasetId).toList();
        throw new HubCliError(HubExitCodes.USAGE,
                "dataset name is ambiguous, pass the dataset id: " + datasetRef + " (candidates: "
                        + String.join(", ", candidates) + ")");
    }
}
