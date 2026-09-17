---
name: ai-scene-sheet
description: Location reference assets that hold one place across many shots — a canonical master plate per location plus a reverse angle, detail inserts and day/night states, a verbatim location block (openings, furniture positions, materials, light direction), the reverse-angle procedure that stops layouts from flipping, interior vs exterior obligations, reusing real photos, and drift repair by axis. Use for any multi-shot work set in the same place.
---

# Location References

Models keep no persistent 3D location — no floor plan, no prop inventory, no sun position. Every generation re-infers a plausible room from whatever prompt and references it gets, and text is underdetermined: "Sarah's apartment, living room" describes thousands of valid rooms. That is why a location survives one shot and falls apart across cuts.

Every credible pipeline locks the same two layers:

1. **Visual layer** — one approved canonical plate per location, re-fed into every generation (first frame, reference slot, or img2img base).
2. **Text layer** — a location block pasted verbatim into every shot prompt. Paraphrasing it is a bug, not a style choice.

The shot prompt is the delta on top of both.

## The location pack (4–6 files per recurring location)

| Asset | What it shows | How it is used |
|---|---|---|
| **Canonical master** | Wide establishing shot: geometry, dominant light, defining objects; nothing blocking the set, static or a very slow push | Source of truth — `role=first_frame` when a shot must match it, otherwise `role=scene` |
| **Reverse angle** | The opposite wall(s) the master never showed | `role=scene`; the asset that stops reverse shots from inventing a room |
| **Detail inserts (1–2)** | Shelf, mirror, desk corner, counter, signage — one anchor each | `role=prop`; proves a hero prop without claiming geography |
| **Light/state variant** | Same layout, different light: night, golden hour, overcast, practicals on/off | `role=scene`; swapped in when the scene's time of day changes |

- **One job per file, one role per reference.** Overlapping references get averaged, and averaging is itself a drift source.
- **Name every plate and never rename it**: `@loc_apartment`, `@loc_apartment_rev`, `@loc_apartment_night`, `@prop_counter`. Names make a plate recallable in a prompt and make drift detectable.
- Keep the pack small — video models in this gateway take 4–5 image references (more only on the large-budget models), so 4–6 files per location is the working size, not a minimum to exceed.
- **Skip the pack for throwaway places.** Insert shots and montage locations need no references; spend the pass on locations that recur.
- One lighting grammar, one aspect ratio, one model version per location — mixing models mid-location reintroduces drift by itself.

## Building the pack (generate_image)

Canonical master:
```
[location]. Wide establishing shot of the empty [room/street], no people.
[Which wall has the window / the door / the counter], [furniture with positions],
[materials and colours], [key light direction and colour temperature].
Static composition, eye level, 24mm look, [style words].
```

Reverse angle — request it in the same session, immediately after the master:
```
Same [room] as the reference, now shot from the opposite side: camera at [far corner]
looking back toward [the wall the master showed].
The opposite wall contains [what was never seen]: [door], [shelf], [window].
Same materials, same light direction, same time of day.
Do not mirror the room. Do not redesign the layout. No people.
```

Detail insert:
```
Same [room] as the reference. Close-up of [the anchor prop] — [material, wear,
position relative to the room]. Same light direction, same time of day.
```

State variant (night, practicals on):
```
Same [room], same furniture layout, same camera position as the reference.
Only the light changes: warm 2700K practical lamps, windows dark, cool spill from the street.
Do not move, add or remove anything.
```

Verify every plate with `caption_image` before it enters the pack — same room? same light direction? no invented openings? A wrong plate poisons every downstream shot, and downstream shots cannot repair it.

## The location block (paste verbatim, never paraphrase)

Write it once per location; it is the text half of the lock. Keep it front-loaded and shorter than the scene direction that follows it.

- **Openings per wall, as relations**: "red door on the south wall", "two salt-streaked windows on the east wall". Relations out-perform lists and out-perform the word "same".
- **Furniture with positions**: "green velvet 3-seater sofa centre-back, brass floor lamp to its right, dark wood coffee table in front of it".
- **Materials and palette**: floor, walls, glass, metal, textiles.
- **Light source, direction, colour temperature** — and whether practicals are on.
- **Hero props, fixed vs movable**: "a cream lamp owns the left end of the desk; notebooks and the mug may move".
- **One committed camera height per location** — eye level, low, slightly elevated — written down and never varied silently.
- **Weather / surface state** for exteriors: "wet pavement", "light rain", "wind from camera left" — not "moody storm atmosphere".

```
LOCATION: [name]. [Which wall has what], [furniture with positions], [materials],
[light source + direction + colour temperature], [hero props and their state].
Preserve the floor plan, openings, materials, prop positions and light direction.
Do not mirror, redesign, add or remove fixed elements.
```
Then the shot's own instruction: camera, framing, action.

## Reverse angles: never improvise the opposite wall

A reverse is not a mirrored prompt — it is the other side of the same room, which means describing what the master never showed. Unseen geometry gets invented: extra windows, moved shelves, mirrored layouts.

In order:

1. **Surface the unseen wall before you need it.** Decide what is behind the character, add it to the location block or generate the reverse plate first.
2. **Hold the 180° line** with screen-direction and eyeline wording: "eyeline matches the previous shot, looking screen-right toward the window". Keep the character's screen-left/right position stable across cuts and never flip the action axis inside one scene.
3. **Generate the reverse in the same session as the master** — geography carries forward; returning to that location in a later session is where drift starts.
4. **Fix camera geography with a floor plan.** One line per shot against the same plan: "the wide looks east from the counter; the reverse stays by the booth with the east window behind her". A plan is what prevents mirroring.
5. **Promote the approved reverse to a reference plate** — once it exists it stops being improvised.
6. **Build new angles from the master via img2img**, not from text alone.
7. **Verify against the same geography**: can the room be drawn from the shots you have? Did anything move because the story caused it, or because the model improvised?

## Interiors vs exteriors

Interiors hang on openings and practicals: window count and position, door position, wall and floor materials, what the windows show outside, whether the outside light is changing.

Exteriors add four obligations:

1. **Façade and architecture anchors** — building geometry, door and window colours, street furniture, pavement pattern.
2. **Identity landmarks** — landmark, horizon, vegetation, sun or moon direction.
3. **Weather with physical consequences** — rain, snow, dust, fog, wind, wet reflections, condensation. A wind that blows curtains in one shot must blow them in the next, or not at all.
4. **Explicit scale cues** in wide spaces: "subject small in the frame" instead of hoping background blur hides wrong geography.

## Light and state continuity

**One Light Source Rule**: state the light's position once ("key light from screen left, 3200K"), copy that exact string into every shot prompt, and change only intensity or contrast for drama — never move the source. Lighting drift is the most visible form of scene inconsistency, and mood wording ("golden mood") drifts where physically specific wording does not.

- Build variants from the canonical plate by editing only the lighting text; the layout stays locked.
- **Day and night are separate assets**, not one asset with a prompt switch — mandatory on models that bind references as elements.
- Record the light *state*, including practicals: if the lamp is knocked over in shot 7, shot 8 opens with it off and on the floor.

## Using real photos and live-action frames

A real photo pins what text cannot: actual architecture, materials, light.

- **Preserved**: walls, windows, doors, ceiling height, layout, camera angle.
- **Changeable**: furnishing, finishes, wall colour, lighting, time of day.
- Say explicitly what must not leak: real-person identity, bystanders, watermarks, resolution artefacts.
- Decide per shot whether the reference **guides** (layout and materials, composition free) or **matches** (an exact view — then it is a frame, not a reference). Mixing the two roles produces rigidity or drift.
- Distrust inputs the model cannot read: very dark rooms, motion blur, extreme wide angle, shots through a doorway, and mirrors or large glass that compete with the room itself.

## Panels for humans, separate files for models

Multi-panel sheets are the planning and selection instrument, and a poor input for a video model: a grid is read as one busy picture, panels lose per-panel resolution, and baked-in labels survive into the motion as garbled lettering.

- Export panels as **separate files** before they enter a video model; label each by role.
- If a grid must be the input: one lighting grammar, no text or panel numbers, no mixed aspect ratios, clean export, and keep the cell-by-cell manifest beside the file.
- Don't grid what is already locked.

## Drift repair

Diagnose the axis first — layout, props, lighting, surfaces, motion — then touch only that axis.

| Symptom | Fix |
|---|---|
| Shot 2 is a different room | Re-feed the canonical plate; check the location block was pasted verbatim |
| Furniture moved or rearranged | Position-explicit inventory ("centre-back", "left wall"); re-feed the plate; regenerate only that shot |
| Wall colour or texture drift | Lock palette and materials; stop lighting wording from leaking into material description |
| Prop disappears, duplicates or teleports | Add a detail insert to the pack; list it in preserve and negative lines; check count, position and colour per shot |
| Room gets bigger or smaller | Commit camera height and focal-length feel per location; write camera positions against the floor plan |
| Extra windows, mirrored layout | The reverse was improvised — build the reverse plate; negative "extra windows, reversed room, moved shelves" |
| Light flips day/night or side to side | One Light Source Rule; check practicals per shot |
| Background slides like a flat plate | Slow the move, keep it lateral, strengthen 3D cues and reference weight |
| Background shimmers or morphs inside a clip | Shorten the clip, simplify the action, start from a locked frame |

1. Change **one variable per retry** — changing cast, light, lens, action and camera together hides the cause.
2. **Fix the reference, not the shot.** A wrong plate propagates downstream; correct it there and regenerate only what is affected.
3. **Verify at the cut point**: freeze the last frame of one clip and the first frame of the next. Furniture, wall colour, window position and key props must agree, and the light must read as one room.
4. Budget rerolls with a stop-loss; colour-match residual exposure and tone drift in the edit rather than re-rolling.
5. Check continuity every 3–4 shots, not at the end of the sequence — catching drift at shot 3 costs one regeneration, at the end it costs ten.

## Cross-skill

- Characters: `ai-character-sheet` — the same two-layer lock, applied to faces.
- Camera moves: `ai-camera-language`. Lighting and look: `ai-lighting-looks`. Coverage exploration: `ai-contact-sheet`.
