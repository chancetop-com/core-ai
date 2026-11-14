package ai.core.server.quality;

import java.util.List;
import java.util.Map;

/**
 * The capability projection shared by the gate and (for parity testing) the runtime registry. Capability
 * ids are stable domain names; the tool names behind them come from the same builtin registry runtime uses.
 *
 * @author core-ai
 */
public record EffectiveCapabilityView(List<String> toolNames,
                                      Map<String, Boolean> capabilities,
                                      Map<String, String> capabilitySource) {
    public boolean has(String capability) {
        return Boolean.TRUE.equals(capabilities.get(capability));
    }
}
