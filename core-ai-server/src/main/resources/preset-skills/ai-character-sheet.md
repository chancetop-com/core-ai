---
name: ai-character-sheet
description: Character reference assets and the pipeline that holds a face across shots — the reference pack (canonical portrait, face close-up, headless front full-body, back view, turnaround, expression grid) with the prompt that generates each, sheet hygiene, the verbatim identity block with role-labeled references, the measured fact that named traits survive while unnamed ones decay, ranked drift fixes, per-model reference ceilings for 2026, the video handoff (frames vs references, clip length, the fix ladder), what is wasted effort, and the house rules for scale, lighting and aspect ratio. Use for any task that needs the same person across images or shots.
---

# Character references

Consistency comes from **reference images, not from description** — but a description still decides what the references cannot protect. Name the traits you cannot lose; anchor the rest with clean assets.

Consistency is a property of the pipeline, not a prompt trick. Four layers, each with one owner:

| Layer | Owns | Asset |
|---|---|---|
| Identity | Face, hair, body, marks | This pack — locked once, reused byte-identical |
| Look | Style, grade, palette | A style anchor or locked style line (`ai-lighting-looks`, `ai-scene-sheet`) |
| Story | Pose, action, camera, place | Storyboard or keyframes, then video (`ai-shot-language`, `ai-contact-sheet`) |
| Polish | Small repairs | An edit model — never a re-roll of identity |

Three rules follow, and most failures are a violation of one of them:

1. **Lock once, reuse forever.** Approve one canonical set, then re-attach the *exact same files* to every generation. Never swap the reference mid-project, and never overwrite the master with a "better" rollout.
2. **Change one variable at a time.** Scene, action and light vary; the identity text and the files do not. Descriptions are locked word-for-word — "auburn" and "reddish-brown" read as two different people.
3. **Reference beats text — but text must not fight the reference.** Keep the identity block aligned with the sheet and describe only what the reference cannot show: the scene. Re-describing the face while a reference is attached makes the two argue, and the model invents a compromise.

## The reference pack

| Asset | Its one job | Verdict |
|---|---|---|
| Canonical portrait (or full-body) | The face, and the design | Essential — keep **exactly one**. Several "ideal" faces make the model average them |
| Face close-up, head-and-shoulders | Identity carrier | Holds best; this is the only face asset |
| Front full-body, **head cropped at the chin** | Wardrobe, silhouette, shoes | Useful: with no head the model can only take the face from the close-up |
| Back full-body | Back of the hair, coat back, straps | Useful: vet the invented back once, then lock it |
| Turnaround, front / three-quarter / side / back in one row | Three-dimensional consistency | Useful when the asset will be re-angled |
| Expression grid, 2 rows × 3 columns | Face plasticity | Holds best in measurement |
| Props panel | Draws the signature object once | Only when the character owns a prop |
| Pose sheet, 9-panel poster | — | **Wasted effort**: three of four poses unusable, detail crushed (a notebook renders at 32 px on a sheet versus 71 px as a single) |

```
1. Canonical: Create a single full-body image. Framing: vertical 3:4, whole character head to toe,
   relaxed pose, arms slightly away from the body, plain light grey background, soft even light.
   Avoid any face that resembles a real or famous person. (generate 4-6, keep one)
2. Face: Head-and-shoulders close-up of this exact character: [face shape, eyes, skin, hair
   details], neutral expression, plain light grey background, soft even light.
3. Front: Full body, head cropped at the chin, front view, relaxed neutral pose, arms slightly
   away from the body, plain light neutral background. Same height and scale as the other views.
4. Back: Full body, back view, same height and scale as the front view, relaxed neutral pose.
5. Turnaround: Create a turnaround: front, three-quarter, side and back in one row, all the same
   height, standing on one baseline, every detail on the same side of the body in every view.
6. Expressions: Six head-and-shoulders portraits in a grid of two rows and three columns, evenly
   spaced, same scale: neutral, happy, angry, sad, surprised, embarrassed - same face shape and
   hairstyle in every panel, no text, no labels.
```

Build it in **two stages**: draw all views in one pass so they agree with each other, then feed that sheet back as the reference and redraw each view individually at full resolution — consistency from the sheet, resolution from the redraw. Generating front, then side, then back one by one drifts between cuts, and a dense panel sheet crushes detail; the redraw recovers it. The individual redraws are what you mount as reference files; the composite sheet is the design artifact.

**House rules**

- Every asset at the deliverable's aspect ratio (3:4 or 4:5 portrait for stills, 9:16 for vertical video); all figures share one scale and one baseline.
- Plain grey or white background, soft even light (~5500 K, no colour cast), **one subject and no prop in hand** — held objects and extra figures create inconsistency across angles, and studio shadows and backdrops leak into every later scene.
- Upload originals, not screenshots or recompressed copies; reference quality bounds output quality, and a face too small to read is the most common drift cause.
- **Never promote a drifting image into the pack**, and vet the invented back of the hair and coat once before locking it. A silently swapped master invalidates the run.
- Test drift with close-ups before wides — wide shots hide a failing face until it is too late to fix cheaply.
- **Expression grids are image-stage only — never mount them as video references.** Six panels is the ceiling; re-run detail-critical frames as singles.
- Verify each asset before mounting it (same person? same wardrobe? face visible?) rather than assuming the set is coherent.
- **State variants are separate packs**: wet, bloodied, changed, aged — each gets its own face/front/back set with an explicit name (for example `LinWan_wet`). Never describe several states in one prompt.
- After the pack exists, the scene prompt becomes: `Show the character from the attached sheet in [scene]. From the sheet, keep the face, proportions, outfit and colours exactly the same, and change only the pose and expression. One image, the character once, without the sheet's labels, swatches or extra poses.`

## The identity block

Reuse verbatim in every shot:

```
IDENTITY (unchanged): [Name], [age], [gender/ethnicity], [face shape], [eye colour and shape],
[brows/nose/lips], [skin tone and marks], [hair colour, texture, length, parting],
[distinguishing feature], [signature accessory]. Same person and identical facial features as
the reference - do not invent, restyle or alter the face.
WARDROBE LOCK (unchanged): [outfit, colours, materials].
SCENE (the only part that changes): [location, action, camera, light].
```

- **Bind every reference to one role and name it**: `@Image 1 is the character`, `@Image 2 is the location`. Close with the anti-reinterpretation clause: *"use the provided image as the authoritative reference — do not redesign, age-shift, or beautify."* Unlabeled references get averaged and "use this as reference" is too vague to defend a face.
- **Named traits survive; unnamed ones decay.** In a measured test, traits named in the prompt held 100% across runs, while reference-only traits decayed — a notebook and a strap mostly held, but hair asymmetry showed in only 14 of 26 front views. Name what you cannot lose, and put identity on objects the character keeps.
- **Placement**: keep the same order every time (description → action and setting → style). Do not alternate synonyms between shots. For stills use the full block; for reference-conditioned video compress it to a one or two-line "same person as the reference" anchor, since long descriptive text pulls the model off the anchor. Video models that are not reference-conditioned need the full block.
- Wardrobe changes are new assets, not new sentences.

## Drift fixes, in order

1. **Edit the exact wrong panel in the source sheet** — everything downstream inherits the fix.
2. Regenerate reference-first (the sheet or an approved still), never text-first.
3. Frame-chaining: export an approved face frame and use it as the next shot's reference; hand off the last frame for shots longer than five to ten seconds.
4. Open a fresh session when accumulated follow-up drift sets in, and re-attach the sheet.
5. Re-run the failing frame alone at full resolution.
6. Name the specific missing detail. (Trade-off warning: fixing the back of a coat once cost two front details.)
7. Swap in a closer face view for stubborn shots.
8. Change one variable per retry. The working checklist: prompt consistency → isolate the problem area → describe it more strongly → upload past successes as references → lower variation.
9. When references conflict, reduce them — the strongest face plus the body is often enough.
10. Seeds are diagnostics, not guarantees: neither a repeated prompt nor a fixed seed ensures continuity.
11. Design around blind spots: detail carried only in silhouette is the first thing lost.

## Reference ceilings (2026-09)

| Model family | Feature | Reference ceiling |
|---|---|---|
| Nano Banana Pro class | Multi-reference plus conversational editing — the best pack builder and repair tool | ≤14 references, ≤5 people |
| Veo 3.1 | Ingredients to video | 3 references officially, 4 on some surfaces |
| Kling | Subject binding, Elements | 4 references per clip, 1–4 images per element |
| Midjourney V7 | `--oref` omni reference with `--ow` weight | 1 image; the older character-reference flag does not work in V7 |
| Seedance 2.x | Multimodal reference-to-video with appearance lock | Around 9 images plus video and audio references |
| GPT Image 2.5 class | Subject preservation across edits | Not published |

More slots do not mean better consistency: two to four well-chosen references usually outperform a large set, and conflicting references average into a stranger.

## Video handoff

- **Frames and references are different inputs.** Frames buy local control — exact composition and identity at the boundary instants; references buy flexibility — the model re-stages the subject for you. Identity-critical shots default to frames. On APIs that separate them, the two styles cannot be mixed in one call.
- **Approve the still before paying to animate.** Fix a drifting shot by adjusting the motion prompt first; regenerate the source still only when the visual design itself is wrong. Video is the expensive step, and the image stage exists to catch identity failures before you pay to animate them.
- **Prompt motion, not identity.** With a first frame attached, the text carries action, camera, environment motion and audio; appearance belongs to the reference.
- **Re-attach the master to every shot.** Chaining clip→clip alone compounds drift; when you chain, keep the returned frame *and* the master, and never overwrite the master with it. Clip length converges on ~8 seconds — single-pass identity holds 10–20 seconds at best; a stricter camp cuts 2–3 second micro-shots on the logic that every new frame is a chance to deviate.
- **The fix ladder, cheapest first**: relabel reference roles → restage in a medium shot before attempting close-ups, emotion or angle extremes → adjust the motion prompt → reject and re-roll → swap or regenerate the sheet.
- Budget reality: about **three generations per usable shot** and roughly a keeper per 3–5 attempts; a documented 4K two-character production spent ~5 generations to lock one character. Judge a clip against the brief, not against its best frame — one good frame can hide a broken hand at second eight. Realistic curation gets ~85–90% identity consistency; trained identity (LoRA, Soul ID) is the escalation path past that, at ~95–97%.

## Failure modes

| Failure | Fix |
|---|---|
| Face drifts across shots | Re-feed the sheet, chain from an approved frame, name the missing feature; never re-prompt from text alone |
| Face becomes over-rigid or plastic | Lower variation, add expression guidance, keep at least one natural-pose reference |
| "Same face, different person" (identity right, casting wrong) | Fix the canonical portrait itself; downstream assets cannot repair a bad root |
| Wardrobe or hair drifts | Move the item into the wardrobe lock line and the identity block; describe it as a feature, not a mood; if the reference keeps forcing the old outfit, reduce its scope to face-only (Midjourney `--cw 0`, lower `--ow`) |
| Profile and back shots are a different person | Unseen angles get guessed — add the missing view to the sheet (front/side/back/detail) instead of prompting around it |
| Every shot mirrors the reference's pose or framing | Reference scope or weight is too high: moderate it (`--ow` around 200–400, 500+ only while the face keeps slipping) or regenerate a neutral master |
| Studio backdrop or shadows appear in scenes | Environment bleed from a busy or lit reference — regenerate the sheet cleanly |
| Age shifts | State the age in the identity block and lock skull proportions, eye spacing and nose bridge explicitly |
| Expression lost in video | Reference the expression grid only at the image stage; describe the expression in words for video |
| Real-person likeness | Generate fictional faces; avoid named or celebrity faces in the canonical portrait; treat real-person references as consent-gated |

## Cross-skill

- Location assets: `ai-scene-sheet`. Light and palette: `ai-lighting-looks`. Framing: `ai-shot-language` and `ai-camera-language`. Coverage grids: `ai-contact-sheet`.
