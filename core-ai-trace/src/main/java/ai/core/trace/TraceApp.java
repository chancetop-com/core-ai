package ai.core.trace;

import core.framework.module.App;
import core.framework.module.SystemModule;
import core.framework.mongo.module.MongoConfig;

import ai.core.trace.domain.PromptTemplate;
import ai.core.trace.domain.SchemaVersion;
import ai.core.trace.domain.Span;
import ai.core.trace.domain.Trace;

/**
 * @author Xander
 */
public class TraceApp extends App {
    @Override
    protected void initialize() {
        load(new SystemModule("sys.properties"));

        registerMongo();

        load(new TraceModule());
    }

    private void registerMongo() {
        var mongo = config(MongoConfig.class);
        mongo.uri(requiredProperty("sys.mongo.uri"));

        mongo.collection(Trace.class);
        mongo.collection(Span.class);
        mongo.collection(PromptTemplate.class);
        mongo.collection(SchemaVersion.class);
    }
}
