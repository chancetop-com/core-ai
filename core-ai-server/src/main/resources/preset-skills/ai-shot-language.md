---
name: ai-shot-language
description: Shot framing language that AI video models actually obey — the shot-size, angle and movement vocabulary that lands, crop-line and lens phrasing for real precision, headroom and lead-room wording, the vendor-published prompt ordering contracts (Veo, Runway, Kling, MiniMax, Seedance), shot-to-shot grammar for eyelines and screen direction, and the ranked framing failure modes with fixes. Use whenever a shot's framing must be specified or a frame comes back wrong.
---

# Shot language

## Vocabulary that lands

**Shot sizes** (most reliable parameter after the angle):

| Term | Renders as | Use |
|---|---|---|
| Extreme close-up / macro | One detail fills the frame | tension, texture, product |
| Close-up | Shoulders up | emotion, dialogue |
| Medium close-up | Chest up | dialogue, interviews |
| Medium shot | Waist up | the default workhorse |
| Medium-wide / "cowboy" | Knees up | action with context |
| Full shot | Head to toe | wardrobe, physicality |
| Wide / establishing | Full body plus environment | openings, geography |
| Extreme wide | Subject tiny in frame | isolation, scale |

**Angles**: eye-level, low, high, overhead, Dutch, over-the-shoulder, POV, two-shot. Put the emotional intent here — it is the most obeyed parameter.

**Movements**: push in, pull out, pan, whip pan, tilt, truck, pedestal, orbit, crane, zoom, handheld, static. **Always attach a speed** ("slow push in over five seconds") — models have no default speed.

**Weak wording that gets ignored**: "cinematic", "dynamic", "a nice angle" (produces random drift), anatomical paraphrases ("head to waist" crops at the nostrils), and counts or numbers — Kling's own documentation says models are not sensitive to numbers.

## Framing precision

- **Give the term and the crop line**: "medium shot, framed from the waist up" beats "medium shot" alone.
- **One framing per shot.** "A wide that pushes into a close-up" usually delivers neither; split it into two shots.
- **Lens vocabulary is functional**: 24mm for establishing, 35mm walk-and-talk, 50mm neutral, 85mm for close-ups (compression and shallow depth of field; also the safest for faces — 50–85mm avoids wide-lens stretch). Add shallow or deep focus, bokeh, rack focus, foreground occlusion where they serve the beat.
- **Frame anchors**: eyes on the upper third, headroom above the hairline, and lead room — place the subject on the third opposite the direction of travel, or a pan pushes them out of frame. Add 10–15% padding when movement is planned.

## Prompt ordering

Where a model publishes a contract, follow it:

| Model family | Order |
|---|---|
| Veo, Runway | camera first: shot size → angle → movement with direction and speed → subject and action → lens and look → lighting → what the shot reveals |
| Kling | subject → subject movement → scene → camera language + lighting + atmosphere |
| MiniMax / Hailuo | reference alignment first, then camera motion written as a natural part of the action — never stacked labels at the end of the sentence |
| Seedance | preservation → subject → action → camera → mood, with named references per file |

Cross-platform synthesis:

- **Attach every directive to the clause it modifies.** Camera specs that float at the end of a sentence get diluted.
- Lead with the camera spec when a model over-weights early words; put style and audio last.
- Roughly 40–80 words per shot. Shot size plus one move plus one action is the working ceiling — constraint pile-up degrades all of them.
- **In image-to-video, never re-describe the frame** that the image already fixes: describe motion only, since composition fixes belong in a new first frame.

**Weak versus specified** — the difference is almost always vocabulary, not adjectives:

```
weak:   cinematic dynamic shot of the CEO looking powerful
better: Medium close-up, framed from the chest up, slight low angle, 85mm lens. The CEO stops
        mid-step and turns her head to camera. Slow push in over four seconds, one move only.
        Hard practical light from screen right. She realises the contract is gone.
```

**Shot list for one beat** — write it out before generating anything:

```
Shot 1 (master): wide, eye level, 24mm - establish the room; she enters frame left, stops at the desk.
Shot 2: medium shot, framed from the waist up, 50mm - she opens the folder; static camera,
        motion comes only from her hands.
Shot 3: close-up, 85mm - eyeline screen right toward the door; slow push in.
```

## Shot-to-shot grammar

- No model keeps a persistent camera rig, so write continuity into every shot: "A is on screen-left looking right; B is on screen-right looking left".
- **Master shot first.** Establish the geography wide, then cover — that is what makes the closer shots land in the same space.
- Keep every camera on one side of the line for a scene, and log screen direction outside the tool so a later session cannot flip it.
- **Over-the-shoulder spec includes**: whose shoulder, which side of frame, roughly a third of the width, what must stay clear, and the subject's eyeline.
- **Match across cuts**: screen sides, gazes, shoulder pattern, camera height, head size, lens family, lighting side and set anchors.
- **Cut only for new information** — a change of space, state, viewpoint or time. A mere size change is better served by a push-in. Keep dialogue singles short (4–6 seconds) and low-motion; hard cuts usually beat crossfades on generated footage.

## Failure modes

| Failure | Fix |
|---|---|
| Shot size ignored, drifts to the model's default | Add the crop line and lens; lock the first frame; change one variable per retry |
| Head or hands cropped | Medium or medium close-up plus explicit headroom, or lock a reference frame |
| Eyeline breaks or the composition mirrors | Master shot first, per-shot screen direction, working-side instruction, continuity checklist |
| A "static" shot drifts | "Camera entirely motionless for the duration; movement only from the subject" |
| Two or more moves warp the frame | One move per clip — reliability degrades sharply per added axis; two moves is the ceiling |
| Identity drifts across cuts | Verbatim character block, same reference, similar crops; re-roll rather than re-prompt |
| Prompt fights the UI sliders | Keep one source of truth per parameter |
| Image-to-video prompt fights the frame | Motion only; if the composition is wrong, generate a new first frame |
| "Cinematic" produces mush | Name the technique, the lens and the lighting instead |
| Wide shots unstable | Use wides for geography only, anchor with silhouettes, then cut closer |
| Subject pushed out of frame during a move | Offset them to the opposite third and phrase the move as tracking the subject |

## Community disagreements worth knowing

- Camera first versus last versus in-place: the vendor contracts disagree; the safe rule is to attach each instruction to its own clause rather than front-loading a block of moves.
- One move per clip versus compounds: compound presets exist and marketing claims they work; measured reliability still degrades with each added axis.
- Rule of thirds versus centre bias: models recentre by default, so slight off-centre framing is safer than extreme thirds — and a reference image is the only reliable enforcement.
- Image-to-video "motion only" versus anchor-then-action: follow the contract of the model being used.

## Cross-skill

- Camera movement techniques and multi-shot structure: `ai-camera-language`.
- Location geography behind the eyelines: `ai-scene-sheet`. Faces: `ai-character-sheet`. Light: `ai-lighting-looks`.
