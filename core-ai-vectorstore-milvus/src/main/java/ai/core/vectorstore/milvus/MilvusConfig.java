package ai.core.vectorstore.milvus;

/**
 * @author stephen
 */
public class MilvusConfig {

    public static Builder builder() {
        return new Builder();
    }

    String uri;
    String token;
    String database;
    String username;
    String password;
    String collection;             // default collection, may be null
    String contentField = "query"; // field that Document.content is written to / read from
    Integer connectTimeoutMs = 10_000;

    public static class Builder {
        private String uri;
        private String token;
        private String database;
        private String username;
        private String password;
        private String collection;
        private String contentField = "query";
        private Integer connectTimeoutMs = 10_000;

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

        public Builder collection(String collection) {
            this.collection = collection;
            return this;
        }

        public Builder contentField(String contentField) {
            this.contentField = contentField;
            return this;
        }

        public Builder connectTimeoutMs(Integer connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
            return this;
        }

        public MilvusConfig build() {
            var config = new MilvusConfig();
            config.uri = this.uri;
            config.token = this.token;
            config.database = this.database;
            config.username = this.username;
            config.password = this.password;
            config.collection = this.collection;
            config.contentField = this.contentField;
            config.connectTimeoutMs = this.connectTimeoutMs;
            return config;
        }
    }
}
