package ai.core.cli.helper;

import java.util.ArrayList;
import java.util.List;

/**
 * Shape of {@code ~/.core-ai/helpers.json} — the user-level list of helpers shared by the CLI
 * and the desktop engine.
 *
 * @author stephen
 */
public class HelperConfig {
    public Integer version = 1;
    public List<HelperEntry> helpers = new ArrayList<>();
}
