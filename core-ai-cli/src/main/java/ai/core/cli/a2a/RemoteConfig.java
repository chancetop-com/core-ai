package ai.core.cli.a2a;

import core.framework.api.json.Property;

/**
 * Plain data holder for connecting to a remote A2A agent — which server, which agent,
 * display name. Credentials come from {@code AuthConfig}, never stored here; the old
 * {@code ~/.core-ai/remote.json} persisted form was removed with the CLI remote mode.
 *
 * @author stephen
 */
public record RemoteConfig(@Property(name = "server_url") String serverUrl,
                           @Property(name = "agent_id") String agentId,
                           @Property(name = "name") String name) {
}
