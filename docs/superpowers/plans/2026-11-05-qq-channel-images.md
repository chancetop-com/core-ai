# QQ Channel Images Implementation Plan

> **For agentic workers:** Use the established test-first workflow for each owned component; root integrates and independently verifies the combined change.

**Goal:** Deliver QQ image bytes through the HTTP channel into the existing model attachment and sandbox staging pipeline.

**Architecture:** Patch the pinned OCG 1.3.3 HTTP transport at sandbox-image build time to serialize structured media as inline image parts. Parse the latest user message on the server and import its images into the existing multimodal object storage before publishing a channel command. Reuse `ChatMessageAttachments` and its sandbox persistence behavior.

**Tech Stack:** Java/JUnit/Gradle, Node.js built-in test runner, sandbox Dockerfile.

**Spec:** Approved repair discussion for trace `08276b989072c802311cda1453598910`; the source image exists in the OCG sandbox but the HTTP transport and channel command currently carry only text.

## Constraints

- Accept inline image data from structured gateway media, never infer file access from user prose.
- Maximum 10 images and 6 MiB total decoded image bytes, leaving room under the server's 10 MB request-body limit.
- Remote-only image references produce an explicit unsupported-source error in this scoped implementation; no arbitrary URL downloader is introduced.
- Keep channel attribution and pure-text behavior; image-only input must not reuse older user text.
- No production deployment, package publication, Git commit, or business-data write is part of this code change.
- New Java files use author `xander`, class-level English Javadoc, and explicit imports.

## Tasks

- [x] Gateway: reproduce the text-only HTTP payload, verify the QQ plugin's structured media fields, implement an exact-version build patch and executable tests for both HTTP modes, pure text, image-only, multi-image, invalid/missing/oversized media and HTTP failure delivery.
- [x] Storage: test and implement `ChannelImageAttachments.store(List<String>)`; validate the full batch, import to `ai/uploads/`, return standard attachment metadata and a public URL or signed fallback, and clean up partial imports on failure.
- [x] Channel: test and implement latest-user multipart parsing, preserve all text/image parts, extend `channelSendMessage` with attachment metadata, and bind the importer before the controller.
- [x] Integration: verify imported bytes reach `ChatMessageAttachments`, native vision input and the non-native `caption_image` path through existing consumers. Run targeted Java and Node tests plus relevant module checks.
- [x] Review: independently inspect the combined diff and resolve defects. Report code verification separately from live deployment and real-model acceptance.

## Review Focus

- Real QQ media fields reach both asynchronous and synchronous HTTP transports.
- Image-only messages cannot select a previous user's text or image.
- A transfer failure is visible to the sender, including non-2xx responses in async mode.
- Declared MIME, byte signatures, request limits and partial-storage failures do not cause silent loss.
- Caption URLs are reachable references rather than gateway filesystem paths; the existing sandbox restoration contract remains intact.

## Verification Results

- `./gradlew :core-ai-server:check :core-ai:ciCheck --console=plain`: passed. Server: 2,046 passed, 132 skipped; core: 1,194 passed, 27 skipped.
- `node --test core-ai-sandbox-runtime/ocg/test/http-images.test.mjs`: 23 passed.
- The original QQ JPEG was read from its gateway without modifying the live environment; local forwarding preserved all 118,777 bytes and SHA-256 `3c0d9b986ae6b9480ae83ae46e55d756f25320ec2825e0293e7a4be6e731318b`.
- Full `:core-ai:check` additionally runs `spotbugsTest`, which reports existing findings in untouched `CompressionTest`, `FfmpegImageShrinkerTest`, `WriteFileToolTest`, `LLMModelContextRegistryTest`, `CompressionEventDispatchTest`, `BackgroundTaskManagerTest`, and `ToolExecutorTest`. No core source or tests were changed.
- Independent code review found no blocking issue. Successful imports can remain unreferenced if later session creation or publication fails; publication errors are ambiguous, so those objects must not be deleted automatically.
- Docker image build, deployment, and a live QQ/vision-model round trip remain outside this local code verification. Activation instructions are in `core-ai-sandbox-runtime/ocg/README.md`.
