# OCG HTTP image forwarding

OCG 1.3.3 retains media in its mailbox transport but its HTTP transport forwards
only `BodyForAgent` text. QQ images therefore reach Core AI as a path in the
gateway sandbox, which the agent sandbox cannot read.

The sandbox Dockerfile applies this patch after installing the pinned npm
package. The installer checks the package version, the complete upstream
`dist/inbound.js` SHA-256, and every replacement site. A different release or
modified source fails the build and requires a fresh compatibility review.

The patch uses the plugin's structured `MediaPaths` / `MediaUrls` / `MediaTypes`
fields. It does not parse user text to find files. For HTTP requests it sends
standard `image_url` parts containing image bytes, and preserves the text part.
An exact leading QQ `- Images:` hint for the forwarded files is removed; other
metadata and user text remain. Mailbox and ACP retain their existing behavior.

## Contract

- Local PNG, JPEG, GIF and WebP images, or inline `data:image/...;base64,...`
  values; MIME is detected from bytes.
- At most 10 images, with at most 6 MiB of decoded image bytes in total. Base64
  therefore fits below Core AI's 10 MiB request limit for normal message text.
- The channel plugin must download remote images first. HTTP(S) image references
  produce an explicit error; this patch does not add an outbound URL fetcher.
- Explicit non-image media and readable non-image files lacking a MIME type
  retain the previous text behavior. Missing, malformed or oversized images
  produce an error reply instead of a text-only agent request.
- Async HTTP rejection is reported to the sender instead of being ignored.

## Verification

Run the executable upstream-transport regression tests:

```bash
node --test core-ai-sandbox-runtime/ocg/test/http-images.test.mjs
```

The original failure can be replayed against the unmodified upstream fixture:

```bash
OCG_TEST_UNPATCHED=1 node --test --test-name-pattern='sync HTTP forwards the actual QQ' core-ai-sandbox-runtime/ocg/test/http-images.test.mjs
```

That command intentionally fails because the unpatched request has only text.
Tests replace external transport dependencies and inspect the generated HTTP
request; they do not contact QQ or an LLM. Fixture provenance and licensing are
recorded in `test/fixtures/NOTICE.md`.

## Activation

Deploy the server-side channel attachment support and rebuild the sandbox
runtime image using the repository root as the Docker build context. Existing
OCG sandboxes need to be replaced with sandboxes running the rebuilt image.
A server rollout or a gateway process restart alone does not replace the
installed gateway code. Running `ocg upgrade` inside a sandbox can overwrite
the patch; rebuild and review a newly pinned version instead.

These local checks do not establish a live QQ or vision-model round trip. That
requires a deployed runtime, a new image message, and a successful image or
`caption_image` result in the corresponding trace.
