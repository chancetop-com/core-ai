/**
 * @author stephen
 */
object Versions {
    const val CORE_FRAMEWORK_VERSION = "9.4.2"
    const val JACKSON_VERSION = "2.20.1"
    const val UNDERTOW_CORE_VERSION = "2.3.23.Final"
    const val OKHTTP_VERSION = "5.3.2"
    const val MUSTACHE_JAVA_VERSION = "0.9.10"
    const val MILVUS_JAVA_VERSION = "2.6.8"
    // milvus-sdk-java 2.6.8 pins grpc 1.59.1, while core-ai's google-auth-library -> google-http-client pulls grpc-context 1.66.0.
    // Gradle then upgrades grpc-api alone and grpc-core 1.59.1 fails at runtime (NoClassDefFoundError: io/grpc/InternalGlobalInterceptors).
    // The BOM keeps every io.grpc artifact on one version; keep it >= the grpc-context version required by google-http-client.
    const val GRPC_VERSION = "1.66.0"
    const val HNSWLIB_JAVA_VERSION = "1.2.0"
    const val JTOKKIT_VERSION = "1.1.0"
    const val OPENTELEMETRY_VERSION = "1.44.1"
    const val OPENAI_JAVA_CLIENT_VERSION = "4.8.0"
    const val MCP_SDK_VERSION = "1.1.3"
    const val SQLITE_JDBC_VERSION = "3.47.2.0"
    const val SNAKEYAML_VERSION = "2.2"
    const val JSOUP_VERSION = "1.18.3"
    const val JLINE_VERSION = "3.26.1"
    const val PICOCLI_VERSION = "4.7.7"
    const val GRAALVM_SDK_VERSION = "25.0.0"
    const val OPENTELEMETRY_PROTO_VERSION = "1.7.0-alpha"
    const val JEDIS_VERSION = "5.2.0"
    const val GOOGLE_AUTH_VERSION = "1.30.0"
}

object ProjectVersions {
    const val CORE_AI_VERSION = "1.4.0-SNAPSHOT"
    const val CORE_AI_API_VERSION = "1.3.0-SNAPSHOT"
}
