---
name: ai-cover-poster
description: Cover and thumbnail key art for vertical short drama — platform safe zones and export sizes (Douyin, Kuaishou, WeChat Channels, TikTok, YouTube Shorts, Bilibili), the one-face-one-emotion composition rules, contrast pairings for reversal and reveal beats, the never-let-the-model-draw-the-title rule with negative prompts and typography specs, three copyable cover prompt templates, the variant-and-select workflow, and the ranked failure modes from UI-zone collisions to clickbait mismatch.
---

# Cover / poster key art

The cover is a poster that has to read at roughly 200×113 px while sitting inside platform UI. Build it with rules, generate 2–6 variants, pick one.

## Canvas and safe zones

Design master: **1080×1920**. Everything below is a crop or a cap of that master.

| Platform | Covered by UI | Keep essentials inside | Notes |
|---|---|---|---|
| Douyin | top ~100–200 px, bottom ~200–350 px, right ~120–150 px | central **1080×1440** (the feed auto-crops to 3:4) | file ≤5 MB, aim ≤500 KB |
| Kuaishou | bottom ~20% | as Douyin | title reads best at 5–8 characters |
| WeChat Channels | outer frame 3:4 | inner 9:16 safe zone; full-screen 6:7 | also export 1080×608 for horizontal cards |
| TikTok / Shorts | top ~15%, bottom ~20%, right ~10% | **centre 60–70%** | keep text 15–18% away from top and bottom |
| YouTube Shorts | — | official art **2160×3840**, min 640 px | JPG/PNG, ≤2 MB for mobile upload, custom Shorts thumbnails only via desktop Studio; verticals with 16:9 thumbs get auto-replaced by 4:5 in the home feed |
| Bilibili | — | 1146×717 | ≤2 MB |

- **Title band**: reserve the top 100–600 px of a 1920-tall master; sub-heads finish by about 800 px. Vendor templates reserve the top 20% and bottom 15%, with the subject inside the middle 1080×1464 band. When sources disagree, honour the larger zone.
- Build one horizontal and one vertical layout, then export 9:16, 3:4, 1:1 and 6:7 from it. sRGB; JPG at 85–95 (PNG when the frame is text-heavy).

## Composition

- **One face, one emotion dialled to 11.** Face at least 40% of frame height (Chinese drama practice: one third to one half), head-and-shoulders crop, eyes on the upper third, 5–8% headroom.
- **Eyeline**: straight to camera for confrontation, or at the object of interest to steer the viewer's gaze. Two people only when the pairing *is* the premise — one face fully readable, the other blurred or turned away.
- Conversion evidence: surprise and happiness dominate top-creator faces; intensity reads better than variety. Closed-mouth determination now outperforms open-mouth shock. Drama-beat defaults: shock, contempt, tearful-but-composed, and the half-smile one beat before the payoff.
- **A face is branding, not a cheat code**: adding a face lifts established creators more than new ones.
- **Contrast pairings** (reversal, exposure, reunion): split the frame; before = desaturated and belittled, after = saturated and in control; carry exactly **one continuity prop** (a ring, a uniform, a bracelet) so the two halves belong to one story; red as the only saturated accent.
- **Readability at thumbnail scale**: 1–2 hero colours; the subject is the brightest, most saturated, most in-focus element; background blurred, darkened, desaturated; a rim light in cyan, magenta or gold to separate the subject; large shapes and thick strokes only — lace, thin fonts and small jewellery simply vanish.
- **Series discipline**: fix the skeleton (title lockup, font, palette family, crop) and vary the beat (emotion, background hue, prop, episode badge). Identical covers depress click-through.

## The text rule

**Never let the model draw the title.** Generate text-free art and set the headline as real type afterwards (Figma, Photoshop, CapCut).

- Put the negatives in every prompt: `no text, no letters, no words, no captions, no subtitles, no watermark, no logo, no signature, no UI elements, no signage`.
- State the positive space too: "clean uncluttered upper third for a title added later" — negatives alone still leave busy shapes where the title must go.
- In-art text only for diegetic marks of one to five words, or short strings on typography-strong models — proofread character by character. Never for the title, a logo, a date, a price or an episode number.
- Cover copy only when it adds information beyond the title; over-claiming headlines test click-through-negative. Chinese coverage runs ≤8–12 characters (5–8 on Kuaishou), English 3–4 words. Chinese type at 60–80 px and English at 80–120 px on a 1080-wide master, with a 2–4 px stroke or a plate, and contrast of at least 4:1.

## Templates

**A — single-face conflict freeze-frame** (9:16, text-free):

```
Vertical 9:16 short-drama cover key art, 1080x1920. Same lead as the attached character sheet:
[hair], [signature garment], [one accessory]. Frozen one beat before the reversal - she has just
been [humiliated/betrayed]; chin lifted, eyes locked on camera, ONE dominant emotion: defiant
fury, mouth closed. Head-and-shoulders crop, face >=40% of frame height, eyes on the upper third,
5-8% headroom. Background: blurred desaturated [banquet hall / office], darker than the subject;
warm gold rim light; warm subject against a cool background; a single crimson accent; large
shapes, minimal detail, poster-grade contrast, photorealistic. Top third clean for a title added
later; bottom 15% and right 10% clear. No text, no letters, no words, no captions, no subtitles,
no watermark, no logo, no signature, no UI elements, no signage, no borders.
```

**B — before/after reveal:**

```
Vertical 9:16 cover, 1080x1920, split contrast. Left third: same woman BEFORE - plain uniform,
head lowered, desaturated blue-grey grade, blurred antagonist's hand (no readable face). Right
two-thirds: AFTER - red gown, chin up, cold half-smile, eyes to camera, wearing the same
[bracelet] seen on the left (single continuity prop). Thin diagonal light streak between the
halves; two figures maximum, one face readable; background simplified and darkened; red is the
only saturated accent; large shapes. Top third clean for the title; bottom 15% and right 10%
clear. No text, no letters, no watermark, no logo, no signage, no UI.
```

**C — expression sheet** (build once, reuse all season):

```
Character expression sheet, 4x2 grid, same actress as the attached reference, head-and-shoulders
in every cell, identical framing, lighting, hairstyle and wardrobe, plain grey background.
Emotions: defiant glare to camera; restrained fury with jaw clenched; tearful but composed; cold
triumphant smirk; shock with mouth closed; heartbroken with lip trembling; contemptuous side-eye;
reunion smile with wet eyes. Photorealistic, consistent identity across all cells. No text, no
letters, no watermark.
```

## Workflow

Build the character sheet once (four angles plus a wardrobe list) and the eight-cell expression sheet; pass them as references with a fixed seed and repeat the identity anchors. Generate 2–6 variants per episode, then select with a one-second message test, a "twenty feet away at 40% brightness" emotion test, a safe-zone collision check, a contrast pass, and 3:4 plus 16:9 crop checks. Upscale before typesetting, add the lockup and episode badge, and keep the layered source and sheets for the next episode.

A/B two or three variants where the platform allows it (YouTube's Test & Compare is desktop-Studio only, does not support Shorts, stops on mid-test edits, and drops every variant to 480 p if any thumbnail is below 720 p). Chinese practice is faster: swap weak covers within hours to days.

## Failure modes

| Failure | Fix |
|---|---|
| Title sits under platform UI | Guide template: top ≥100 px, bottom 15–25% and right 10–15% clear; verify over a real app screenshot |
| Garbled AI lettering | Never generate the title; real type in post; `no text` negatives; if in-art text is unavoidable, quote it exactly and proofread |
| Expression misread at small size (a smirk reads as pain) | One exaggerated emotion; the 20-foot / 40%-brightness test; keep the most extreme legible take |
| Wrong crop — chin cut, face too small, face under the side buttons | 9:16-native generation, face ≥40%, 5–8% headroom, nudge the subject off the UI column |
| Fake signage or props collide with the title zone | Targeted exclusions (`plain wall, no signage, blank screen`), name the negative-space zone, inpaint leftovers instead of regenerating |
| Subject merges into a muddy background | Blur, darken and desaturate the background; rim light; keep the subject the brightest, most saturated element |
| Over-sharpened uncanny faces | Fix at generation (structure, denoise) and keep skin texture; no clarity or HDR-glow sliders |
| Clutter — three faces and a badge soup | One focal subject; at most two figures with instantly readable roles; one badge plus one title |
| Fine detail lost at scale (lace, thin fonts, small jewellery) | Large shapes and thick strokes only; a 30-second native-size phone preview before publishing |
| Cover does not match the episode | Show the real peak of the episode — mismatch tanks completion and invites both audience backlash and algorithm penalties |

## Cross-skill

- Faces and wardrobe: `ai-character-sheet`. Location backdrops: `ai-scene-sheet`. Light and colour: `ai-lighting-looks`. Framing terms: `ai-shot-language`.
