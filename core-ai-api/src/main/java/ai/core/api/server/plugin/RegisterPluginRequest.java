package ai.core.api.server.plugin;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * Publish/update a plugin registry entry. Identity, version and capabilities are derived from the
 * manifest, so the registry cannot drift from the package it points at.
 *
 * @author stephen
 */
public class RegisterPluginRequest {
    @NotNull
    @Property(name = "manifest")
    public String manifest;

    @NotNull
    @Property(name = "download_url")
    public String downloadUrl;

    @NotNull
    @Property(name = "sha256")
    public String sha256;

    @Property(name = "size")
    public Long size;

    @Property(name = "vendor")
    public String vendor;

    @Property(name = "homepage")
    public String homepage;
}
