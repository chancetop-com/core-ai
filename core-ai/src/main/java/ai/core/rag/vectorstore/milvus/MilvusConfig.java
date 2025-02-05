package ai.core.rag.vectorstore.milvus;

import core.framework.internal.module.ModuleContext;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author stephen
 */
public class MilvusConfig {

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final Logger logger = LoggerFactory.getLogger(Builder.class);
        private ModuleContext context;
        private String name;
        private String uri;
        private String token;
        private String database;
        private String username;
        private String password;

        public Builder context(ModuleContext context) {
            this.context = context;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder uri(String uri) {
            this.uri = uri;
            return this;
        }

        public Builder token(String token) {
            this.token = token;
            return this;
        }

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder password(String password) {
            this.password = password;
            return this;
        }

        public Builder database(String database) {
            this.database = database;
            return this;
        }

        public MilvusClientV2 build() {
            this.logger.info("create milvus client, name={}", this.name);
            var config = ConnectConfig.builder()
                    .uri(uri)
                    .token(token)
                    .username(username)
                    .password(password)
                    .dbName(database).build();
            var milvus = new MilvusClientV2(config);
            this.context.shutdownHook.add(6, timeout -> milvus.close());
            return milvus;
        }
    }
}
