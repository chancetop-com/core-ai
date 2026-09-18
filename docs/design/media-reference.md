# Media Reference Design

Referencing previously generated images and videos in a follow-up generation call.

## Motivation

Multi-turn media editing ("make the same character, but remove the glasses") is the
dominant image workflow, but the platform has no first-class way to say *"the image you
just made"*. The agent has to copy a URL out of the previous tool result's markdown and
paste it back into `input_images`. Every layer of that round trip has failed in
production at least once:

| Failure | Cause |
|---|---|
| `failed to download reference image: HTTP 307` | artifact URLs answer 307 to a pre-signed URL; the shared HTTP client has `followRedirects(false)` |
| `KIE API error: msg=File type not supported` | the platform artifact URL was forwarded to KIE, which cannot reach `localhost:8080` |
| `File type not supported` (upload) | `uploadReferenceImage` generated a fileName with no extension |
| `http request failed, ... recordInfo, error=null` | `retryOnConnectionFailure(false)`, no application-level retry on the first poll |
| `gateway model does not support endpoint` | the edit model was registered under `image.generations` |

Each was fixed individually. They share one root cause: **the reference is a URL chosen
at the tool layer, but only the routing layer knows which provider will receive it, and
what that provider can actually consume.** A URL that is perfect for one destination
(our own storage, for inlining) is unusable for another (KIE, which must fetch it from
the public internet).

Two structural gaps make this worse:

1. **Images have no handle.** Videos get an opaque `video_id`
   (`GatewayVideoHandle.encode(jobId)`) that the agent passes to `get_video_status` and
   `previous_video_id`. Images get a markdown link. The only durable identifier for a
   generated image lives in conversation text.
2. **`MediaJob` already knows everything we need** — `userId`, `sessionId`, `fileId`,
   `providerId`, `upstreamVideoId`, `resolvedModel` — and nothing at the tool layer can
   see it.

## Goals

- Reference prior output symbolically, never by copying a URL through the LLM.
- Move the representation decision to where the destination provider is known.
- Use the cheapest faithful path available: provider-native continuation > upstream-side
  URL > pre-signed public URL > inline base64.
- Keep an escape hatch for genuinely external images.
- Scope references to the caller (a handle is an authorization check; an arbitrary URL is not).

## Non-goals

- Cross-session references. A handle resolves within the owner's own jobs.
- Persisting provider-native interaction chains beyond what providers already keep.
- Changing the `MediaProvider` SPI shape (`ImageGenerationRequest` keeps `List<MediaReference>`).

## Design

### 1. One handle type for both modalities

Generalize `GatewayVideoHandle` into `GatewayMediaHandle` — same base64url encoding, a
modality-aware prefix, still backed by a `MediaJob` id:

```
gateway-media-v1.img.<base64url(jobId)>
gateway-media-v1.vid.<base64url(jobId)>
```

`gateway-video-v1.<...>` keeps decoding for backward compatibility (persisted video ids
are already in flight).

`generate_image` starts returning the handle alongside the display link:

```
Image generated. Include this exact Markdown image link in your final response:

![Generated image](https://.../api/public/artifacts/<token>/content)

media_id: gateway-media-v1.img.ZTMzZTk5ZDkt
```

This mirrors what `generate_video` already does with `video_id`, and gives the agent a
short token to echo instead of a 120-character URL.

### 2. `MediaRef` — the wire format

Both tools accept the same reference shape. `input_images` and `input_references`
converge on it (`input_references` keeps its name as an alias).

| Field | Meaning |
|---|---|
| `name` | stable author-time handle used in the prompt (`char_lin`), see §3 |
| `role` | `subject` \| `scene` \| `style` \| `camera` \| `audio` \| `prop` |
| `media_id` | a specific earlier generation |
| `url` / `b64Json` | external content, escape hatch |

Plus two shorthands that expand to the above: `"last"` (most recent completed media of
that modality in this session) and `"attached"` (images on the current chat message).

`"last"` and `media_id` become the documented default for editing; URL/base64 drop to
"external content only". The tool description states the rule, and the model-capability
tags (`[image-to-image]`) added earlier tell the agent which model can accept them.

### 3. Prompt-level addressing (`@mention`)

Multi-reference video models do not infer what each attached asset is for — they require
the prompt to assign a role per reference. Seedance 2.5 addresses them as `@image1` /
`@video1` / `@audio1`; MiniMax H3 uses `<Picture 1>`; other families use `[Image1]`.
Every published prompt guide says the same thing: *every reference needs one clearly
scoped role, stated in the prompt.*

```
@image1 defines the woman's face and green jacket only.
@image2 defines the coffee shop counter and the window light only.
@video1 supplies only the camera path and timing.
```

Crucially, the token↔asset binding is **positional**: `@image1` means
`reference_image_urls[0]`. KIE's API spec documents no token field at all — the arrays
are the binding, the tokens are a prompt convention the model was trained on. So the
array builder and the token generator must agree, or the model silently puts the wrong
face on the wrong body — a failure with no error at all.

#### Author with names, render with tokens

The agent writes semantic names, never positional tokens:

```json
{
  "prompt": "参考 @char_lin 的角色，放进 @scene_cafe 的场景，用 @shot_dolly 的运镜生成视频",
  "input_references": [
    {"name": "char_lin",   "media_id": "gateway-media-v1.img.…", "role": "subject"},
    {"name": "scene_cafe", "media_id": "gateway-media-v1.img.…", "role": "scene"},
    {"name": "shot_dolly", "media_id": "gateway-media-v1.vid.…", "role": "camera"}
  ]
}
```

The gateway compiles this against the target model: orders the arrays, assigns positional
tokens in the model's own syntax, and rewrites the prompt.

```
Seedance 2.5   →  "参考 @Image1 的角色，放进 @Image2 的场景，用 @Video1 的运镜生成视频"
MiniMax H3     →  "参考 <Picture 1> 的角色，放进 <Picture 2> 的场景，用 <Video 1> 的运镜…"
Seedream 5 i2i →  "参考第一张参考图的角色，放进第二张参考图的场景…"   (no addressing syntax)
```

Names rather than raw tokens, because:

- The agent cannot know the target syntax, and should not — it is a model-family detail
  that already lives in a registry.
- **Trimming renumbers.** When references exceed a model's limit some are dropped; with
  positional tokens every later token then points at the wrong asset. Names let the
  compiler renumber correctly and report *which name* was dropped.
- Switching models between turns renumbers everything; names survive.
- A model with no addressing syntax must have the tokens *removed*, not passed through —
  a stray `@char_lin` in a Seedream prompt is prompt pollution.

Raw `@image1` in the prompt still works: if references carry no `name`, position is the
name. It is accepted, not documented.

#### Reuse `ReferenceCompiler`

This logic already exists and is already correct — in `drama/render/ReferenceCompiler`.
It assigns per-modality indexed tokens in three syntaxes, trims by role priority against
per-modality and mixed limits, and **reports every dropped reference explicitly** (its
own comment: *"silent trimming causes the 'but I DID give a reference image' confusion"*).
The generic `generate_image` / `generate_video` path has none of it.

The move is to lift `ReferenceCompiler` out of the drama module into the shared media
layer and have drama consume it, rather than building a second one. Its capability input
(`DramaModelCaps`: `maxImages`, `maxVideos`, `maxAudios`, `maxMixedTotal`,
`addressingSyntax`, `acceptsAudioRef`) becomes the media capability descriptor described
in §5, with `NONE` added to the addressing enum for models that have no token syntax.

### 4. `MediaReferenceResolver` — resolution happens after routing

This is the core change. Today:

```
GenerateImageTool                    GatewayMediaProvider
  parse input_images                   route(model, endpoint) ─── provider known HERE
  download URL  ← decision made        upstream.generateImage(...)
  build MediaReference                    ← decision needed HERE
```

After:

```
GenerateImageTool                    GatewayMediaProvider
  parse into MediaRef (no I/O)  ──▶    route(model, endpoint)
                                       resolver.resolve(refs, route, owner)
                                       upstream.generateImage(...)
```

`MediaReferenceResolver` sits in `ai.core.server.gateway`, next to
`MediaProviderAdapterFactory`, and takes `(List<MediaRef>, GatewayRoute, MediaJobOwner)`
→ `List<MediaReference>`. It resolves in four tiers, cheapest first:

**Tier 1 — provider-native continuation.** The source job's `providerId` equals the
target route's provider, and the provider supports conversational editing
(`VERTEX_GEMINI_INTERACTIONS` today). Emits no reference at all; instead sets
`previousInteractionId` on the request. Zero bytes moved, best fidelity — the provider
keeps its own latents. This is the path `previous_video_id` already takes for video, now
available to images and selected automatically instead of requiring the agent to know.

**Tier 2 — upstream-side asset reuse.** The source job was produced by the *same*
provider and its upstream asset is still valid. KIE result URLs already live inside KIE's
own storage, and its API additionally accepts `asset://{assetId}` in the reference arrays
(*"Enter a list of image URLs or asset://{assetId}"* — Seedance 2.5 spec), which is a
first-class, non-expiring handle. Handing one back costs nothing and survives longer than
a signed URL. Requires `upstreamAssetRef` + `upstreamAssetExpiresAt` on `MediaJob`.

**Tier 3 — pre-signed public URL.** The target provider fetches URLs from the public
internet (KIE, and any `*_url` field provider), and the source is in our object storage.
`FileService.downloadUrl(record)` already mints a pre-signed URL (Azure SAS / MinIO,
~1h). This is the fix for the localhost problem: the provider gets a URL it can actually
reach, and we move zero bytes. Never cached — minted per call, because the signature
expires.

**Tier 4 — inline base64.** The provider requires inline data (OpenAI images, Gemini
`inlineData`), or the source is not in object storage. Read bytes, base64, attach mime
type. This is today's only path, demoted to last resort.

Provider capabilities driving tier selection are declared once, next to the adapter:

```java
record MediaProviderCapabilities(boolean acceptsRemoteUrl, boolean acceptsInlineData,
                                 boolean supportsInteractionChaining) { }
```

keyed by `mediaProtocol` — the same switch `MediaProviderAdapterFactory.create` already
has. No provider-specific logic leaks into the tools.

### 5. Media capability registry

Tier selection (§4) and reference compilation (§3) both need per-model facts. They are
model-family properties, not provider properties — Seedance and MiniMax both arrive via
KIE yet address references differently.

| Fact | Used by |
|---|---|
| `acceptsRemoteUrl` / `acceptsInlineData` / `supportsInteractionChaining` | tier selection |
| `maxImages` / `maxVideos` / `maxAudios` / `maxMixedTotal` | trimming |
| `addressingSyntax` (`AT_TOKEN` \| `BRACKET` \| `ANGLE_SUBJECT` \| `REF_TAG` \| `NONE`) | token rendering |
| `acceptsAudioRef` | audio drop + TTS fallback |

Drama already made the right call here — *"a new model is one registry row, never code"* —
and stores this in Mongo. Generalize the same way: these become admin-editable fields on
`gateway_model`, seeded from a code-level table keyed by upstream model prefix, exactly
as `MediaModelParameterHints` already keys hints today. Adding Seedance 3 is then a row,
and the hint text the agent sees stays derived from the same source as the enforcement.

### 6. `MediaJob` becomes the reference index

`MediaJob` is already written for every generation and already carries owner, session,
provider and file. It needs three additions:

| Field | Why |
|---|---|
| `upstream_asset_url` | tier 2 reuse |
| `upstream_asset_expires_at` | tier 2 validity |
| `upstream_interaction_id` | tier 1; images have no `upstreamVideoId` equivalent |

`"last"` resolves as: newest `MediaJob` with `state=completed`, matching `mediaType`,
matching `sessionId`, owned by the caller. That query needs an index on
`(session_id, media_type, state, completed_at)`.

Two existing behaviours become load-bearing and must be tightened:

- `MediaJobService.storeImage` currently skips persisting when `b64Json` is null
  (`MediaJobService.java:108`). With tier 3 the provider may return only a URL, so
  images would stop being stored exactly when we start depending on it. Storing must
  fall back to downloading the result URL.
- Video jobs never populate `fileId` until someone calls `downloadVideo`. Tier 3/4 for
  video references needs the bytes; resolve lazily and cache into `fileId` on first use.

### 7. Failure semantics

Handle resolution failures are *deterministic and actionable*, unlike today's transport
errors:

| Condition | Result |
|---|---|
| unknown / malformed handle | `BadRequestException`, names the handle |
| handle owned by another user | `ForbiddenException` (today: an arbitrary URL is fetched with no check) |
| `"last"` with no prior media | `BadRequestException` telling the agent to generate first |
| source job not completed | `BadRequestException` naming the state |

External `{"url"}` references keep the existing loader — including redirect following and
the size cap — but that path is now the exception rather than the norm, which shrinks the
SSRF surface to explicitly-external references.

## Migration

Backward compatible throughout; no flag day.

1. `GatewayMediaHandle` added, `GatewayVideoHandle` delegates to it. Existing
   `gateway-video-v1.*` ids keep working.
2. `MediaJob` gains three nullable fields. Old jobs resolve via tier 3/4 as today.
3. Tools accept `media_id` / `"last"` **in addition to** URL and base64. Nothing that
   works today stops working.
4. `MediaReferenceResolver` takes over representation choice; `GenerateImageTool.resolve`
   and `GenerateVideoTool.resolveReference` shrink to parsing, no I/O.
5. Tool descriptions promote handles and demote URLs.

Step 4 is the one that removes the class of bug, because after it no component chooses a
representation without knowing the destination.

## What this fixes, structurally

| Today's patch | Why it is no longer needed |
|---|---|
| follow 307 in `HTTPReferenceImageLoader` | own-storage references never go through the public artifact endpoint |
| drop the URL in `GenerateImageTool.resolve` | the resolver picks per-provider instead of guessing once |
| derive an upload extension from the mime type | tier 3 hands KIE a pre-signed URL; the upload path is used far less, and when it is, mime comes from `FileRecord.contentType` rather than a parsed data URL |
| agent copying a URL out of markdown | `"last"` / `media_id` |

## Implementation status

Implemented. What landed, and where it differs from the sketch above:

| Section | Where |
|---|---|
| §1 handle | `GatewayMediaHandle`; `GatewayVideoHandle` is now a video-flavoured view of it |
| §2 wire format | `MediaReference` itself carries `mediaId` / `name` / `role` / `modality`; `MediaReferenceParser` does the tool-argument parsing |
| §3 addressing | `MediaReferenceCompiler` + `MediaPromptAddressing` (shared), driven by `GatewayReferenceCompiler` |
| §4 resolver | `MediaReferenceResolver`, `MediaProviderCapabilities` |
| §5 capability registry | `MediaCapabilityRegistry` (code seed) overlaid with `gateway_model` rows, admin-editable |
| §6 reference index | `MediaJob.upstream_interaction_id` / `upstream_asset_url` / `upstream_asset_expires_at`, `SchemaMigrationVMediaJobReferenceIndex` |

Three deliberate departures:

- **No separate `MediaRef` type.** The design's §4 signature (`List<MediaRef> → List<MediaReference>`)
  cannot also carry the §3 prompt rewrite and drop report. Rather than add a second reference type to
  the SPI, `MediaReference` gained the symbolic fields and the resolver returns
  `Resolved(references, interactionId)`, with `GatewayReferenceCompiler.Compiled(prompt, references, notes)`
  as a separate stage. `ImageGenerationRequest` still holds a `List<MediaReference>`, as the non-goal required.
- **`"attached"` is not a resolver shorthand.** Attached content lives in `ExecutionContext`, which
  the gateway cannot see, and an attachment URL is a *platform* URL — handing it to a provider that
  fetches from the public internet reintroduces exactly the localhost failure §4 exists to remove. It
  stays inlined at the tool layer.
- **Video references need modality on the wire.** §3's own example passes a video for the camera role,
  which the old `List<MediaReference>` could not express — every reference went into
  `reference_image_urls`. `MediaReference.modality` fixes that, and `KieMediaProvider` now splits into
  `reference_image_urls` / `reference_video_urls` / `reference_audio_urls` while preserving the array
  order the tokens were compiled against.

Not covered, and still true: video jobs never populate `upstream_asset_url`, so tier 2 only fires for
images; a video reference resolves via tier 3/4 on the lazily-downloaded, then cached, file record.

## Open questions — resolved

1. **Should `"last"` be the implicit default when the model is an `[image-to-image]` one
   and no reference is given?** It would make the common case zero-argument, but it
   re-introduces the implicit-attachment ambiguity deliberately rejected for
   `input_images="attached"`. Leaning no.
2. **Interaction chaining across a model switch.** Tier 1 requires the same provider. If
   the agent switches Seedream → gpt-image-2 mid-conversation we silently drop to tier
   3/4 with different fidelity. Worth surfacing in the tool result, or worth refusing?
3. **Retention.** Pre-signed URLs and `MediaJob` outlive the session. Do handles expire
   with the session, with the file record, or never?
4. **`n > 1`.** A job maps to one `fileId` today. Multi-image results need either a
   handle per image (`...img.<jobId>#1`) or a job per image.
5. **Unmentioned references.** Every prompt guide insists each reference gets a role
   sentence. If a named reference never appears in the prompt, do we (a) reject, (b) warn
   in the tool result, or (c) auto-append `"@Image2 provides the scene."`? (c) is the most
   helpful and the most intrusive — the gateway would be editing the user's prompt.
6. **Name collisions with real text.** `@char_lin` is unambiguous, but a prompt containing
   an email address or a social handle would be mangled by naive substitution. Require
   names to be declared in `input_references` and only rewrite declared ones (planned), or
   require an explicit delimiter?
7. **Trim reporting.** `ReferenceCompiler` already returns `DroppedReference` with a
   reason. Should a drop be surfaced to the agent as a partial-success note in the tool
   result, or escalated to a failure so the agent re-plans with fewer references?

### Resolutions

1. **Implicit `"last"` for `[image-to-image]` models.** No, as the doc leaned. A reference must be
   asked for; the ambiguity rejected for `input_images="attached"` is the same ambiguity here.
2. **Chaining across a model switch.** Silent drop to tier 3/4, not a refusal — refusing would make a
   legitimate model switch fail. The fidelity change is not currently surfaced; if it turns out to
   matter it belongs in the same `notes` channel the trim reports use.
3. **Retention.** Handles never expire on their own; they live as long as the `MediaJob` and its
   `FileRecord`. A handle whose file record is gone fails with `media reference has no stored content`.
   Pre-signed URLs are minted per call and never cached, so their expiry is not a handle concern.
4. **`n > 1`.** Still one job per call, and the job stores the first image. `generate_image` reports
   `media_id (first image)` rather than implying the handle covers the batch. A handle per image needs
   a job per image; not done.
5. **Unmentioned references.** (b) — reported in the tool result. Auto-appending a role sentence means
   the gateway editing the user's prompt, which is a bigger commitment than the problem warrants.
6. **Name collisions with real text.** Declared names only, plus a lookbehind that refuses to treat
   `@name` as a mention when it continues an identifier or an email local part. `user@char_lin.com`
   and `@char_linked` are both left alone; names are restricted to `[A-Za-z0-9_-]{1,64}`.
7. **Trim reporting.** Partial success, not failure. A drop is reported in the tool result and the
   dropped name is rewritten out of the prompt, so the remaining tokens still address the right
   assets. Failing the call would throw away a generation that is usually still what the user wanted.
