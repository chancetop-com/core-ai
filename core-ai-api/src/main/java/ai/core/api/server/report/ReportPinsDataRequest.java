package ai.core.api.server.report;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author core-ai
 */
public class ReportPinsDataRequest {
    @Property(name = "pin_ids")
    public List<String> pinIds;

    @Property(name = "range_kind")
    public String rangeKind;

    @Property(name = "days")
    public Integer days;

    @Property(name = "from")
    public String from;

    @Property(name = "to")
    public String to;

    @Property(name = "page_size")
    public Integer pageSize;

    @Property(name = "offset")
    public Integer offset;

    @Property(name = "refresh")
    public Boolean refresh;
}
