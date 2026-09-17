---
name: ai-contact-sheet
description: Coverage grids for shot selection — generating a 3×3 or 2×2 set of camera setups for one scene in a single image, the cell-by-cell prompt enumeration that stops the model repeating the same framing, panel-size and resolution reality checks, how to pick and extract a panel at full resolution, when to split a grid before animating, and why a grid is a planning asset and not a video input. Use before committing to a shot's composition.
---

# Coverage grids

One image buys nine framings. The grid is a **selection instrument**: you explore compositions cheaply, pick one, then generate that shot properly. It is not a deliverable and not a substitute for identity or location references.

## Templates

**3×3 camera coverage** (the default):

```
Analyze the reference images; identify the subject and the spatial relations.
Generate ONE 3×3 contact sheet, 9 panels: same subject, same wardrobe, same location,
same lighting and colour grade in every panel. Vary the camera only:
row 1 - extreme wide / wide / medium-wide (knees up);
row 2 - medium (waist up) / medium close-up (chest up) / close-up;
row 3 - extreme close-up / low angle / high angle.
Photoreal textures, realistic depth-of-field shifts, thin neutral gutters between panels,
no text labels, no numbers. [style words]
```

**2×2 keyframe grid** for a beat you will animate:

```
A 2×2 storyboard grid, four panels in reading order, one continuous scene.
Panel 1: wide establishing, subject enters frame left.
Panel 2: medium shot, subject centred, [the staging that matters].
Panel 3: close-up on the face, same wardrobe and lighting.
Panel 4: wide from the opposite angle, subject exits frame right.
Identical subject, wardrobe, lighting and location in all four panels.
No text overlays, no panel numbers, no watermarks.
```

**Continuity add-ons** (append when useful):

- `Interpolate smoothly between these views, never cut.` — only when you want motion rather than cuts.
- `Silently inventory and lock identity, wardrobe, hair and makeup, set geometry, light direction and quality, colour grade, aspect ratio.`
- Repeat the lock late: `Every panel shows the SAME subject, wardrobe, lighting and location.` Attention decays over a long prompt, so the first statement alone is not enough.
- Put geometry into words: "cobalt seamless meets the floor one third from the bottom", "hard on-axis flash, vignetted edges".

## Rules that make a grid usable

1. **Enumerate every cell** by shot name, framing and beat. "A 9-panel storyboard, cinematic" produces nine versions of the same medium shot.
2. **State rows × columns, reading order and thin gutters** — otherwise panels merge into one image.
3. **Supply references** (character sheet, location plate) and keep the reference aspect ratio equal to the output aspect ratio; a 9:16 reference sent to a 16:9 grid crops heads.
4. **Decide the audience for the file.** For humans, labels and numbering are fine. For anything a model consumes later, forbid text: baked-in labels survive into video as garbled lettering.
5. **Cap at nine panels.** Quality thins beyond that, and each cell is only as large as the grid allows — a 3×3 in a 2K image gives cells around 680 px, which is below what image-to-video wants.
6. **A grid is not a character sheet.** The image model re-renders the person in every cell rather than compositing pixels, so faces drift; pass the original reference photo alongside when identity matters.

## From panel to finished shot

- Choose by composition, eyeline, headroom and continuity with the previous shot — not by which pane is prettiest.
- **Extract the chosen panel at full resolution** (grid-capable models accept an extraction instruction naming the row and column, up to 4K). Never crop the grid as the deliverable, and never upscale a small panel before image-to-video — regenerate it.
- If one cell is wrong, regenerate that cell with the grid as its reference, then re-check that the regenerated pane still matches its neighbours.
- Otherwise re-describe the chosen framing in a fresh prompt with the identity and location references attached — usually the cleanest path to a final still.

## Feeding a video model

- Multi-reference video models read a grid as **one busy picture**: per-panel resolution collapses and the panels blur together. Extract panes first.
- Split a nine-grid into three three-panel batches for per-shot control and per-batch regeneration. Three panels for animation, nine for planning and curation.
- Some video models can be told to read a grid as sequential shots, and they then cut between panels; the result is a hard-cut sequence, not one continuous take. Say "interpolate smoothly, never cut" if you want a single take.
- Keep the grid in the planning stage when the model has multi-reference slots available — separate labelled files always read better than one collage.

## Variants beyond camera

- Fixed setup, nine emotions or nine poses — performance selection.
- Nine props or set details — dressing selection.
- Nine shot sizes of the same beat — rhythm planning before committing to a cut order.

## Failure modes

| Failure | Fix |
|---|---|
| Nine copies of the same framing | Enumerate every cell with shot name, framing and beat |
| Panels merge into one image | State rows × columns, reading order, thin gutters |
| Subject drifts in the last panels | Attach references, repeat the identity lock late, cap at nine panels |
| Labels leak into the final video as garbled lettering | "No text overlays, no panel numbers" |
| Panels are read as separate scenes and the video cuts | Add "interpolate smoothly, never cut", or avoid grids for continuous takes |
| Panel too small to animate | Generate at 2K–4K, stay at or below nine panels, split beyond that, never upscale a small pane |
| Geometric 3×3 split misaligns on uneven boards | Detect panels with a vision pass instead of cutting exact thirds |
| Identity drifts even though the prompt said "locked" | Pass the original photo as an additional reference; the model re-renders rather than composites |
| Aspect mismatch crops heads | Match the reference aspect ratio to the output aspect ratio |

**When a grid is wasted effort**: a text prompt already lands the scene, you want one continuous orbit, or exact logos and lettering matter (grids are weak on both).

## Cost

The "one ninth the cost" claim is arithmetic on per-image price plus extraction overhead, not a measured end-to-end study: a 3×3 on a 2K-capable model is roughly an order of magnitude cheaper than nine separate generations. Field anecdotes report 20+ usable shots from three grid generations, and about five generations to lock a character look.

The efficiency rule that holds regardless: **board one panel per story beat, not per cut** — a single storyboard frame can drive a whole multi-shot sequence.

## Cross-skill

- Identity: `ai-character-sheet`. Location: `ai-scene-sheet`. Framing vocabulary: `ai-shot-language`.
