package ai.core.server;

import ai.core.MultiAgentModule;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentRun;
import ai.core.server.domain.AgentSchedule;
import ai.core.server.domain.FileRecord;
import ai.core.server.domain.ToolRegistry;
import ai.core.server.domain.User;
import ai.core.server.domain.migration.SchemaVersion;
import ai.core.server.trace.domain.PromptTemplate;
import ai.core.server.trace.domain.Span;
import ai.core.server.trace.domain.Trace;
import core.framework.module.App;
import core.framework.module.SystemModule;
import core.framework.mongo.module.MongoConfig;

/**
 * @author stephen
 */
public class ServerApp extends App {
    @Override
    protected void initialize() {
        load(new SystemModule("sys.properties"));
        loadProperties("agent.properties");

        // Pass mcp.servers.json to system properties for ToolRegistryService
        property("mcp.servers.json").ifPresent(json -> System.setProperty("mcp.servers.json", json));

        registerMongo();

        load(new MultiAgentModule());
        load(new ServerModule());
    }

    private void registerMongo() {
        var mongo = config(MongoConfig.class);
        mongo.uri(requiredProperty("sys.mongo.uri"));

        mongo.collection(User.class);
        mongo.collection(ToolRegistry.class);
        mongo.collection(AgentDefinition.class);
        mongo.collection(AgentSchedule.class);
        mongo.collection(AgentRun.class);
        mongo.collection(FileRecord.class);
        mongo.collection(SchemaVersion.class);

        // trace collections
        mongo.collection(Trace.class);
        mongo.collection(Span.class);
        mongo.collection(PromptTemplate.class);
    }
}
