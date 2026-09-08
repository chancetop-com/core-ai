include("core-ai")
include("core-ai-api")
include("core-ai-cli")
include("core-ai-server")
include("core-ai-benchmark")

// Vector store backends live under vectorstores/ to keep the repo root focused on core modules.
// Project paths stay flat (:core-ai-vectorstore-*) so build.gradle.kts and published artifactIds are unchanged.
include("core-ai-vectorstore-milvus")
include("core-ai-vectorstore-hnswlib")
project(":core-ai-vectorstore-milvus").projectDir = file("vectorstores/core-ai-vectorstore-milvus")
project(":core-ai-vectorstore-hnswlib").projectDir = file("vectorstores/core-ai-vectorstore-hnswlib")
