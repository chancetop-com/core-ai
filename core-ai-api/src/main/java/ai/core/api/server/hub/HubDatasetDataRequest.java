package ai.core.api.server.hub;

import core.framework.api.json.Property;

/**
 * Record or state data as a JSON object text (e.g. {@code {"merchant_id":"M1","stock":12}}). Text form
 * keeps the view bean free of dynamic value types and lets the server forward the payload untouched.
 *
 * @author stephen
 */
public class HubDatasetDataRequest {
    @Property(name = "data")
    public String data;
}
