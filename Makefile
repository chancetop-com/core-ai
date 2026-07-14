DOCKER_USER = chancetop
IMAGE_NAME = core-ai-server
SANDBOX_IMAGE_NAME = core-ai-sandbox-runtime
VERSION ?= latest
FULL_IMAGE = $(DOCKER_USER)/$(IMAGE_NAME):$(VERSION)
SANDBOX_FULL_IMAGE = $(DOCKER_USER)/$(SANDBOX_IMAGE_NAME):$(VERSION)
DOCKER_DIR = build/core-ai-server/docker
SANDBOX_DIR = core-ai-sandbox-runtime

REPO = chancetop-com/core-ai
CLI_NAME = core-ai-cli

ifeq ($(OS),Windows_NT)
	GRADLEW = gradlew.bat
	DETECTED_OS = windows
	ifeq ($(PROCESSOR_ARCHITECTURE),AMD64)
		ARCH = amd64
	else ifeq ($(PROCESSOR_ARCHITECTURE),ARM64)
		ARCH = arm64
	endif
	CLI_EXT = .exe
else
	GRADLEW = ./gradlew
	DETECTED_OS := $(shell uname -s | tr '[:upper:]' '[:lower:]')
	ARCH := $(shell uname -m)
	ifeq ($(ARCH),x86_64)
		ARCH := amd64
	endif
	ifeq ($(ARCH),aarch64)
		ARCH := arm64
	endif
	CLI_EXT =
endif

CLI_BINARY = $(CLI_NAME)$(CLI_EXT)
CLI_BUILD_DIR = build/core-ai-cli/native/nativeCompile

.PHONY: server sandbox push cli release builder update-model-context

server: builder
	@test -n "$(DOCKER_USER)" || (echo "ERROR: set DOCKER_USERNAME env var" && exit 1)
	$(GRADLEW) :core-ai-server:docker
	docker buildx build \
		--platform linux/amd64,linux/arm64 \
		-t $(FULL_IMAGE) \
		--push \
		$(DOCKER_DIR)

sandbox: builder
	@test -n "$(DOCKER_USER)" || (echo "ERROR: set DOCKER_USERNAME env var" && exit 1)
	docker buildx build \
		--platform linux/amd64 \
		-t $(SANDBOX_FULL_IMAGE) \
		--push \
		$(SANDBOX_DIR)

builder:
	@if ! docker buildx ls | grep multi-builder > /dev/null 2>&1; then \
		docker buildx create --name multi-builder --driver docker-container --use; \
	fi
	docker buildx inspect --bootstrap

cli:
	$(GRADLEW) :core-ai-cli:nativeCompile
	@echo "Built: $(CLI_BUILD_DIR)/$(CLI_BINARY)"

update-model-context:
	curl -fsSL https://raw.githubusercontent.com/BerriAI/litellm/litellm_internal_staging/model_prices_and_context_window.json \
		-o core-ai/src/main/resources/model_prices_and_context_window.json
	@echo "Updated model_prices_and_context_window.json from litellm"

release: cli
	@test -n "$(VERSION)" -a "$(VERSION)" != "latest" || (echo "ERROR: set VERSION (e.g. make release VERSION=1.0.0)" && exit 1)
	gh release create v$(VERSION) --repo $(REPO) --title "v$(VERSION)" --generate-notes || true
	gh release upload v$(VERSION) $(CLI_BUILD_DIR)/$(CLI_BINARY) --repo $(REPO) --clobber
	@echo "Uploaded $(CLI_BINARY) to release v$(VERSION)"

.PHONY: benchmark tbench2 cli-dist install-harbor jre25-linux

JRE25_DIR ?= /tmp/jre25-linux

# Run Harbor benchmarks against core-ai-cli
# Usage: make benchmark DATASET=terminal-bench@2.0 TASK=write-compressor
benchmark:
	@command -v harbor >/dev/null 2>&1 || { echo "❌ harbor not installed. Run: make install-harbor"; exit 1; }
	bash core-ai-benchmark/harbor/run.sh $(DATASET) $(TASK)

# Build CLI install distribution (for Harbor benchmark)
cli-dist:
	$(GRADLEW) :core-ai-cli:installDist

# Install Harbor CLI via uv
install-harbor:
	uv tool install harbor

# Download and extract Linux JRE 25 for offline container use
jre25-linux:
	@echo "Downloading JRE 25 (Linux x64)..."
	mkdir -p $(JRE25_DIR)
	curl -sL "https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jre/hotspot/normal/eclipse?project=jdk" -o $(JRE25_DIR)/jre.tar.gz
	tar -xzf $(JRE25_DIR)/jre.tar.gz -C $(JRE25_DIR) --strip-components=1
	rm -f $(JRE25_DIR)/jre.tar.gz
	@echo "✅ JRE 25 installed to $(JRE25_DIR)"

# Run Terminal-Bench 2 benchmarks via native binary (no JRE needed)
# Usage: make tbench2 TASK=fix-git
#        make tbench2 MODEL=litellm/claude-sonnet-4-20250514
tbench2:
	@command -v harbor >/dev/null 2>&1 || { echo "❌ harbor not installed. Run: make install-harbor"; exit 1; }
	bash core-ai-benchmark/terminal-bench-2/run.sh $(DATASET) $(TASK)
