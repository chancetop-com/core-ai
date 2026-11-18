package ai.core.server.domain;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Server-side plugin registry entry. The package itself stays at {@code downloadUrl}; this document
 * pins identity, manifest and digest so a desktop install can verify what it downloads.
 *
 * @author stephen
 */
@Collection(name = "plugins")
public class PluginRegistryEntry {
    @Id
    public String id;

    @NotNull
    @Field(name = "name")
    public String name;

    @NotNull
    @Field(name = "version")
    public String version;

    @Field(name = "description")
    public String description;

    @Field(name = "vendor")
    public String vendor;

    @Field(name = "homepage")
    public String homepage;

    @NotNull
    @Field(name = "download_url")
    public String downloadUrl;

    @NotNull
    @Field(name = "sha256")
    public String sha256;

    @Field(name = "size")
    public Long size;

    @NotNull
    @Field(name = "manifest")
    public String manifest;

    @Field(name = "required_capabilities")
    public List<String> requiredCapabilities;

    @Field(name = "optional_capabilities")
    public List<String> optionalCapabilities;

    @Field(name = "components")
    public List<String> components;

    @Field(name = "owner_id")
    public String ownerId;

    @NotNull
    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @NotNull
    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;
}
