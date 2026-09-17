---
name: ai-camera-language
description: Master camera-move dictionary — prompt phrasing and narrative use for 24 cinematic moves, plus multi-shot segmentation (timecodes or shot labels), negative-move blocks, an emotion-to-move map, director signatures, and copying a move from a reference video. Use for any video-generation camera description.
---

# Master Camera Language

The prompt equivalent of a professional platform's "master camera" preset. Four things live here: camera words, multi-shot segmentation, negative words, and reference assets. Shot sizes and angles are in ai-shot-language; lighting is in ai-lighting-looks.

## Four iron rules

1. **Put the move at the start of the sentence** ("Pan right across the beach...", not "...camera pans right") — noticeably higher hit rate;
2. **One move per shot**: stacking moves inside one segment (pan + tilt + zoom) usually turns to mush; a compound move is either split into two shots or written as sequential beats in separate segments ("cranes down, then pushes in"). **Sequential segmentation is not a violation**: one prompt may carry several shots, but each shot still holds exactly one move — what breaks is motion stacked inside a single segment, not segmentation itself (see "Multi-shot segmentation");
3. **Every move needs a motive**: reveal, follow, compress, release, isolate, destabilize, or divert attention — one of the seven. No motive, no move: lock the shot off;
4. **Dolly is not zoom**: dolly is the body physically moving (foreground/background parallax), zoom is lens cropping (the frame flattens). For space write `physical dolly, parallax` or `no zoom — physical dolly only`; write zoom only when you want the optical effect.

## Basic moves (push, pull, pan, truck, follow, whip, crane)

| Preset | Prompt | Emotion / use |
|---|---|---|
| Slow push | slow push-in / dolly in | pressure, focus, entering the mind |
| Slow pull | slow pull-back / dolly out | release, isolation, revealing the environment |
| Pan | slow pan left/right, tilt up/down | surveying a space, linking elements |
| Truck | truck left/right, lateral tracking | lateral space, parallel following |
| Follow | tracking shot following subject | companionship, travel |
| Whip | whip pan to [target] | fast transition, sudden jolt |
| Crane | crane up / crane down | opening and closing, sense of fate |
| Locked off | static locked shot | dialogue, negative space, a stare |

## Signature moves (ration them: once per episode)

| Preset | Prompt | Emotion / use |
|---|---|---|
| Hero orbit | low angle hero orbit, 360 orbit around subject, slow motion | hero entrance, awakening, turning point |
| Macro glide | extreme macro shot, 100mm macro, shallow depth of field, camera glides through | title-sequence mood, key prop reveal |
| Crane reveal | slow crane up revealing [environment], dramatic height change | establishing shot, scale reveal, closing elevation |
| Hitchcock | dolly zoom (push in while zooming out) | vertigo, cognitive collapse |
| Crash zoom | crash zoom in | shock, discovery (a hook-shot workhorse) |
| Reveal | camera slides out from behind [occluder] to reveal | corner discovery, reversal |
| Over the shoulder | over-the-shoulder shot | dialogue, establishing a relationship |
| POV | first-person POV | fear, drunkenness, immersion |
| Dutch angle | dutch angle, tilted horizon | instability, mental unbalance |
| Bullet time | bullet-time orbit, frozen moment | spectacle, frozen instant |
| Fly-through | FPV drone flythrough, fly through aperture | travelling space, large scenes |
| Hyperlapse | hyperlapse, moving time-lapse | time compression, change over time |
| Handheld | handheld camera, slight shake | documentary feel, unease, chase |
| Slow motion | slow motion, 120fps look | emotional peak, action highlight |
| Speed ramp | speed ramp from slow to fast | fight beats, reversal reveal |

## Compound formulas

| Formula | Composition | Use |
|---|---|---|
| Hero moment | orbit + slow rise + push in | protagonist awakening, presence |
| Character entrance | back follow + crane up | opening, introducing the lead |
| Reversal reveal | foreground occluder -> truck/crane -> reveal | information reveal, surprise |
| Emotional build | wide -> medium -> close -> close-up (continuous, same direction) | tightening the emotion step by step |

## Multi-shot segmentation: several shots from one prompt

Iron rule 2 is about "no stacked moves inside one shot"; **sequential segmentation is legal and common**: write 3-5 consecutive shots, one move each, hard cut between them. This is the standard way to get a 15-second sequence out of a single prompt.

| Segmentation syntax | How to write it | Fits |
|---|---|---|
| Timecodes | `[00:00-00:04] ...` `[00:04-00:08] ...` | Kling / Veo / Runway read it, second-precise |
| Shot labels | `Shot 1: ...` | Every model understands it — the safest fallback; Jimeng-family models are unreliable with exact timecodes, prefer labels |

Skeleton you can adapt as-is:

```text
[00:00-00:04] Wide shot, slow arc truck left around the subject, industrial alley, cold rim light from the left.
[00:04-00:08] Rack focus from the foreground hand to the face behind it, camera otherwise locked.
[00:08-00:12] Wide shot, slow pull-back pan right, revealing the crowd behind.
[00:12-00:15] Diagonal retreat, crane up into a wide, the street below in frame.
```

- **2-4 seconds per segment is the stable range**; anything shorter than 1.5s gets merged or swallowed;
- **Give every segment the full four axes** (shot size + camera position + move + light) — change the event, not just the move;
- When the total length exceeds the model's limit the tail is dropped first: for a real 15s five-shot sequence pick a long-form model (e.g. seedance 2.5, 4-30s per call);
- Two moves inside one segment: the model usually executes only the first — split before writing.

## Negative move blocks

Most video models have no separate negative field, so **negative constraints go at the end of the prompt** as a comma-separated list. Only kling 2.6 / kling v2 expose a real `negative_prompt` parameter, passed through provider_extra.

| Bucket | Block |
|---|---|
| Text and marks | `text, subtitle, watermark, logo, caption` |
| Move sickness | `camera shake, jitter, random cut, sudden zoom, speed ramp, rolling shutter wobble` |
| Image quality | `blurry, low resolution, oversaturated, plastic skin, flat lighting` |
| Structure collapse | `extra fingers, warped limbs, morphing face, duplicated subject` |
| Scene pollution | `modern objects, anachronistic props, wrong era details` |

- List the **nouns** you exclude; never write a sentence like "do not show ..." — a sentence gets read as part of the positive description;
- Do not fight yourself: if the positive side says `handheld, slight shake`, do not negate `camera shake`;
- The top negatives for camera stability are `jitter, random cut, speed ramp` — most of the "greasy AI video" feel comes from those three;
- Structural negatives (broken people) are enough at 3-5 items at the end; stacking more dilutes the positive description.

## Emotion to move

Decide the emotion first, then pick the move — more reliable than picking a move and inventing a reason for it.

| Emotion / intent | Move | Support |
|---|---|---|
| Pressure, closing in | slow push-in | long-lens compression, low camera |
| Release, loneliness, farewell | slow pull-back + crane up | wide negative space, high camera |
| Shocking reveal, scale | crane up revealing [environment] / reveal shot | foreground occluder transition |
| Vertigo, cognitive collapse | dolly zoom / dutch angle | subjective point of view |
| Power, authority | low angle + slow push / orbit | subject stays still |
| Tenderness, intimacy | handheld micro-shake + slow push in | shallow depth of field, natural light |
| Sudden turn, fast pace | whip pan / crash zoom | hook position in the first 3 seconds |
| Suspense, anticipation | locked-off shot + slow move behind an occluder | sound first |
| Heroism, awakening | low-angle orbit + slow rise + slow motion | once per episode only |
| Time compression, change | hyperlapse / moving time-lapse | fixed camera follow |
| Instability, mental unbalance | dutch angle + handheld | tilted horizon |
| Spectacle, frozen instant | bullet-time orbit | the frozen moment |

## Director signatures

Translating "like director X" into an executable segment structure; use these together with segmentation (on models that ignore timecodes, rewrite the timecode skeletons as `Shot 1` / `Shot 2` labels — the semantics do not change).

| Signature | Prompt skeleton | Use |
|---|---|---|
| Spy-thriller run (arc + rack focus) | Five segments: arc sweep around -> rack focus transfer -> dolly truck -> diagonal retreat with crane up -> wide hold, closing with `cinematic, UE5 render quality, 8K` | Action opening; a whole sequence from one prompt, needs >=10s and segmentation |
| Spielberg intrusion | `Static locked shot, foreground intruder enters the frame out of focus, rack focus from the intruder to the background subject, the subject looks up into the lens` | Suspense into shock, breaking the fourth wall |
| Nolan time stop | `Arri Alexa Mini, 50mm, [00:00-00:03] normal motion, [00:03-00:06] time freezes — debris suspended mid-air, only the camera moves` | Spectacle, showing the rules of physics |
| FPV dive | `FPV drone dive through [space], barrel distortion, motion blur, one continuous take` + negative `camera shake, random cut, speed ramp` | Travelling space, extreme dive |
| Tarantino trunk | `Inside a car trunk looking outward, 45° low angle, trunk walls as a dark frame around the subject, 7000K, static camera` | Trapped point of view, interrogation mood |

A signature piece is a once-per-episode move; mixing more than two in one film turns into camera acrobatics.

## Reference assets: copying a move from a reference video

The cheapest way to copy a move is to **hand over a reference video** and let the model imitate the motion instead of guessing from words. Pass it through `input_references`; the server translates it into whatever the destination model understands.

| Model | images / videos / audios | Prompt addressing | Notes |
|---|---|---|---|
| seedance 2.5 | 30 / 10 / 10 | `@Image1` `@Video1` `@Audio1` | one video 2-30s, total <=30s; the workhorse for long segmented sequences |
| seedance 2 | 4 / 2 / 1 | `@Image1` `@Video1` `@Audio1` | frame mode and reference mode are mutually exclusive |
| minimax-h3/reference-to-video | 4 / 2 / 1 | `<Picture 1>` `<Video 1>` | at least one reference required, aspect_ratio supports adaptive |
| wan/2-7-r2v | 5 / 5 / 1 | no addressing token, refer in natural language | reference videos <=5 |
| gemini-omni | 4 / 3 / 0 | `<IMAGE_REF_0>` `<VIDEO_REF_0>` | each clip <=3s; supports previous_video_id continuation |

All other models (kling, hailuo, pixverse, seedance 1.x, and so on) take no reference video: the reference is dropped at the trim stage and the reason is reported in the result. Beyond the per-modality caps there is a mixed total: 4 items for seedance 2, gemini-omni and minimax-h3/reference-to-video, 5 for wan/2-7-r2v; seedance 2.5 has no mixed cap and only its per-modality caps apply.

```json
[
  {"url": "https://cdn.example.com/camera-move.mp4", "modality": "video", "role": "camera", "name": "cam_move"},
  {"media_id": "gateway-video-v1.vid_xxx", "modality": "video", "role": "camera", "name": "cam_move"}
]
```

- `media_id`: a video produced on this platform (or `"last"` for the most recent one); the server resolves it into an address the upstream can reach;
- `url`: external assets must be **public http(s) direct links**; depending on the target model the platform either hands the link to the upstream or downloads and inlines it (the download path caps a file at 10MB);
- `modality`: one of `image` / `video` / `audio`; it defaults to image when omitted, so **a video reference must state `"modality": "video"` explicitly**;
- Refer to it in the prompt as `@cam_move`; the server rewrites the name into the target model's token (see "Prompt addressing" above);
- `name` accepts letters, digits, `_` and `-` only (<=64 characters); without a name the references are auto-named `image1`, `video1`, ... in order.

Three disciplines:

1. **Give every reference one role sentence**, e.g. `@cam_move defines the camera movement only — ignore its subject and setting`. Multi-reference models do not infer what an asset contributes; leave it out and the asset is applied wrongly, with no error at all;
2. **Never set camera footage as first_frame / last_frame**: those are frame-anchor roles ("the video starts on this picture, ends on that one") and describe the composition of a single frame, not motion; anchors can also be mutually exclusive with reference mode (seedance). Camera references always use `role=camera`;
3. **`role` doubles as the retention priority**: `first_frame > last_frame > subject > scene > camera > style > prop > audio`; when a model's cap is exceeded the tail is trimmed in that order — put the must-have assets on the high-priority roles.

Three fallbacks when the model takes no reference video:

1. **Turn the motion into words**: start frame -> path -> speed -> end frame (all the methods above); read the reference video as a series of single moves and apply segmentation;
2. **First and last frame replication**: `first_frame` + `last_frame` pin both ends of the composition and the motion segment, the model fills the middle (drop other reference images on seedance);
3. **Split the references by job**: subject from a character sheet (`role=subject`), setting from a scene sheet (`role=scene`), light and texture from `role=style`.

Which skill for which asset: characters -> ai-character-sheet; scenes -> ai-scene-sheet; camera -> this skill; shot sizes, angles and composition -> ai-shot-language; lighting -> ai-lighting-looks.

## Writing notes

- **Reference beats words**: if the motion can be copied, do not guess it — reference video > reference image > text;
- **Write speed and duration**: `slow dolly in, 4 seconds, constant speed` beats a bare "dolly in"; 3-5s slow motion is the most reliable, whip pans and crash zooms hold best at 2-3s;
- **Name the start and the end**: state the shot size at both ends ("from wide to close-up on face") so the model knows where the move stops;
- **Moves must brake**: rhythm words in build, accelerate, hold, brake order ("brake into a close-up") remove the constant-drift AI feel;
- **Vertical screens: avoid wide pans**: aspect ratio drives move choice; 9:16 favors push, pull and follow shots.

## Short-video pacing notes

- crash zoom and whip pan are the two best "stop the thumb" tools — reach for them in the first-3-seconds hook shot;
- dolly zoom / rack focus are the "viewers cannot name it but feel it" premium moves — once per episode is enough;
- hero orbit is the highest-impact shot there is: once per episode, main character only;
- crane reveal belongs at the opening establishing shot and the closing elevation;
- macro glide suits an opening logo or prop tease, never two shots in a row.
