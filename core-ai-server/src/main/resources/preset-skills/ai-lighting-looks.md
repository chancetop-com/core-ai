---
name: ai-lighting-looks
description: Lighting and look control for AI image and video prompts — the source-plus-direction formula that replaces "cinematic lighting", twelve reusable lighting presets with the exact prompt strings, project-wide looks with film-stock and hex anchors, the continuity rules that stop light from flipping between cuts, ranked failure modes with fixes, and what to correct in post instead of re-rolling. Use whenever light or colour tone is described.
---

# Lighting and Looks

## The formula

**source + direction + quality + palette anchor.**

"Cinematic lighting" is the single biggest cause of flat, mushy output — it answers none of the questions the model asks. Compare:

```
weak:  cinematic lighting, dramatic mood
works: single hard warm key from camera-left, 3200K, long shadows;
       palette: cream, walnut, cool grey
```

Name the source, not the adjective: "warm light from the table lamps only" beats "warm lighting". Video models read words plus physical consequences ("long shadows", "wet reflections") better than bare Kelvin numbers — pin a number only to stop drift ("3200K, no colour shift").

## Lighting presets

| Preset | Prompt string | Use |
|---|---|---|
| Three-point | warm key 45° camera-left, soft diffused fill, strong warm rim light | standard dialogue, interviews, product |
| Rembrandt | single key 45° camera-left, triangle of light on the shadow cheek, deep falloff | portraits with weight |
| Split | key 90° off the camera axis, half the face in shadow | antagonists, moral ambiguity |
| Butterfly | key directly above and slightly in front, soft shadow under the nose, beauty dish | glamour, retro Hollywood |
| Top-light / interrogation | single bare bulb overhead, hard contrast, the face half in shadow | pressure, noir, dread |
| Window light | soft window light from camera-left, gentle falloff, cool ambient wall behind | domestic, editorial, UGC |
| Neon night | neon sign glow from camera-right, hot pink and cyan on the desk, wet reflections | urban noir, music video |
| Candle / firelight | single candle, warm flicker, dancing shadows, soft halation | period, intimate, ritual |
| Golden-hour rim | sun low behind the subject, warm orange haze, backlit silhouette, long shadows | romance, hero reveal |
| Chiaroscuro | harsh directional light from above-left, deep defined shadow, light only on eyes and cheekbones | menace |
| Overcast softbox | overcast diffused daylight, no visible sun, low contrast | documentary realism |
| Volumetric | fine haze, dust in a shaft of light, hard-edged beam in mist | awe, thresholds, stages |

- Keep one or two light sources in a video prompt, three at the absolute most — more and the light "swims" between frames. Complex rigs are for stills.
- Once a scene's lighting string is approved, treat it as text, not as inspiration: copy it into every shot of that scene.

## Look (one per project)

A look is a project-level decision, not a per-shot effect. Lock it once and reuse the same string everywhere:

| Look | Prompt cues |
|---|---|
| Teal and orange | teal and orange grade, warm highlights, cool shadows, film grain |
| Bleach bypass | desaturated, high contrast, silver-retained, cold |
| Korean cream | cream and beige palette, soft window light, low contrast, slight 35mm grain |
| Neon noir | practicals only, magenta and cyan, wet reflections, deep shadow, halation |
| Pastel high-key | flat telephoto, centred symmetry, pastel palette, even high-key light |
| Desaturated cold | cold cast, dark-to-light 85:15, 2.40:1 hard matte |
| Warm print | Kodak 2383 print, warm mids, teal shadows |
| Cool print | Fuji 3510 print, cool and soft, gentle roll-off |

- Pair a named tonal mode with **hex values** for the anchor colours (amber `#E8A33D`, emerald `#2F6B5B`) so prompt and post grade share one number.
- A DP or film name does about a fifth of the work; the technical cluster does the rest. Never ship a name alone, and cap named references at one per prompt — names also inject unrequested motifs and get diluted as the prompt grows.
- Model routing: Kling, Seedance and Wan reward technique descriptors over proper names (Kling's official formula is subject + movement + scene + camera language + lighting + atmosphere); Veo and Runway respond better to a named DP or film reference; keep the prompt structure constant and change only the routing.
- Keep one model and one look wording per project — changing either mid-project reintroduces drift.

## Continuity

1. **One Light Source Rule.** Fix the light's position in the master prompt, copy that exact string into every shot, and adjust only intensity or shadow contrast for drama — never move the source.
2. **Anchor the light to the set, not to the shot.** "Window on the north wall" survives a reverse angle; a lighting adjective does not. For a reverse, write the mirrored block once and reuse it verbatim.
3. **Master still first, then video.** Start from an approved still so the model prioritises the reference's structure; that is what carries light direction into motion.
4. **Batch, and expect a consistency cliff** — around clip 6–10 with no references, around clip 20–25 with references. Beyond that, plan one harmonising grade over the batch.
5. **Practicals are state, not decoration.** "Lamps lit, tungsten" and "lamps off, moonlight only" are different blocks; write each state you use. Fire is a rate: high flicker, then low, then embers.
6. **Day to night**: name the sky condition, the artificial sources and the mood — never a bare "make it night" — and add "no flicker, temporally consistent". Blue hour is the most reliable target for architecture.
7. Colour-match in post even when the raw clips look consistent.

## Failure modes and fixes

| Failure | Fix |
|---|---|
| "Cinematic lighting" → flat mush | Replace it with source + direction + quality + palette |
| Light direction flips between cuts | One Light Source Rule; verbatim reuse; a mirrored block for reverse angles |
| Colour temperature drifts inside or between clips | Pin Kelvin with "no colour shift"; keep clips short, about 5–8 seconds |
| Look drifts across the project | Anchor set of 3–5 stills, identical style fragment, batch checks, one harmonising grade |
| Night scenes over-lit | "Lamps only", dark ambient, no fill language — the sampler's bias toward a well-lit still lifts faces |
| Oversaturated output | Pull global saturation in post and check skin tone; never fix it with a look LUT |
| Crushed blacks or clipped highlights | Check scopes before grading — a clipped plate is regenerate-class, not grade-class |
| Plasticky, over-sharp skin | Post chain in order: upscale, grade, small blur, grain |

## Bake vs post

AI clips arrive display-referred (Rec.709) with no log curve and no metadata: grade Rec.709 in Rec.709, and never stack a print LUT on already-contrasted footage.

- **Correct first, grade second, never in one pass** — match every clip to one hero shot on scopes, then apply a single creative grade across the batch.
- A LUT is a topcoat, not a primer: 50–70% strength (up to 60–80% for a Kodak or Fuji emulation), grain 8–15%, light blur last.
- Grading cannot invent what was not rendered. Clipped highlights, crushed blacks and wrong textures are regeneration problems.
- Budget about three generations per usable shot and re-roll only the worst outliers; fix continuity at the reference set, not shot by shot.

## Cross-skill

- Location light, windows, practicals, time of day: `ai-scene-sheet`.
- Camera moves and lens: `ai-camera-language`. Faces and identity: `ai-character-sheet`.
