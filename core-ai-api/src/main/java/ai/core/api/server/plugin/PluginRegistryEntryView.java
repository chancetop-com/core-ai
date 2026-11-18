package ai.core.api.server.plugin;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * A plugin package registered on the server. The package bytes live at {@code downloadUrl}; the
 * registry owns discovery + digest pinning, not the binary itself.
 *
 * @author stephen
 */
public class PluginRegistryEntryView {
    @NotNull
    @Property(name = "id")
    public String id;

    @NotNull
    @Property(name = "name")
    public String name;

    @NotNull
    @Property(name = "version")
    public String version;

    @Property(name = "description")
    public String description;

    @Property(name = "vendor")
    public String vendor;

    @Property(name = "homepage")
    public String homepage;

    @NotNull
    @Property(name = "download_url")
    public String downloadUrl;

    @NotNull
    @Property(name = "sha256")
    public String sha256;

    @Property(name = "size")
    public Long size;

    @NotNull
    @Property(name = "manifest")
    public String manifest;

    @Property(name = "required_capabilities")
    public List<String> requiredCapabilities;

    @Property(name = "optional_capabilities")
    public List<String> optionalCapabilities;

    @Property(name = "components")
    public List<String> components;

    @Property(name = "owner_id")
    public String ownerId;

    @Property(name = "updated_at")
    public ZonedDateTime updatedAt;
}
