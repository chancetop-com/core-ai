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

CLI_BINARY = $(CLI_NAME)-$(DETECTED_OS)-$(ARCH)$(CLI_EXT)
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
		--platform linux/amd64,linux/arm64 \
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
	cp $(CLI_BUILD_DIR)/core-ai-cli$(CLI_EXT) $(CLI_BUILD_DIR)/$(CLI_BINARY)
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
