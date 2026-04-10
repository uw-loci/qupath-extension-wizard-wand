# Wizard Wand for QuPath

An enhanced wand tool for [QuPath](https://qupath.github.io/) that adds dwell expansion, edge-aware barriers, automatic hole filling, multiple color spaces, parameter tuning from a ground-truth annotation, and more. The built-in wand is left untouched -- this is a separate, opt-in tool that appears alongside it in the toolbar.

**Requires QuPath 0.6.0-rc4 or later.**

## Installation

1. Download `qupath-extension-wizard-wand-X.Y.Z-all.jar` from the [Releases](https://github.com/uw-loci/qupath-extension-wizard-wand/releases) page.
2. Drop it into your QuPath extensions directory (`Extensions > Installed extensions > Open extensions directory`).
3. Restart QuPath.

The Wizard Wand appears in the toolbar with a sparkle-wand icon and responds to **Shift+W**.

## Quick start

1. Press **Shift+W** (or click the sparkle-wand toolbar button).
2. Click and drag on the image to select a region.
3. Right-click the toolbar button for presets, auto-tuning, and reset.

With default settings, the tool behaves like the built-in wand but fills holes up to 10,000 px automatically. Everything else is opt-in via Preferences.

---

## Features

### Color space modes

Controls how pixel colors are compared when growing the selection.

| Mode | When to use |
|------|-------------|
| **RGB** (default) | Good general-purpose default. |
| **GRAY** | Ignores color, uses brightness only. Best for grayscale or H&E images where you want to select by stain intensity. |
| **LAB_DISTANCE** | Perceptual color distance. Better than RGB when similar-looking colors have different RGB values (e.g., subtle stain differences). |
| **HSV** | Hue-saturation-value. Good when you want to select by color hue regardless of brightness (e.g., all blue nuclei even if some are darker). |

### Sensitivity

Controls how large the selection grows. Formula: `threshold = stddev x sensitivity`.

- **Low (0.1--0.3):** Tight, precise selections for well-defined boundaries.
- **Medium (0.4--0.7):** Balanced. Default is 0.5 (matches the built-in wand).
- **High (1.0+):** Expansive. Use for quickly annotating large uniform regions.

### Gaussian blur (sigma)

Amount of pre-processing blur applied before the wand evaluates pixel similarity.

- **Low (0.5--2.0):** Preserves fine detail; selection follows individual pixel edges. Can be noisy.
- **Medium (3.0--5.0):** Smooths noise while keeping major boundaries. Default is 4.0.
- **High (6.0+):** Heavy smoothing for noisy images or broad tissue regions.

### Dwell expansion (hold-to-grow)

Click and hold the mouse without moving to progressively expand the selection.

- After a configurable delay (default 300 ms), the selection begins growing.
- Growth follows a logarithmic (decelerating) curve: fast at first, then slowing.
- Moving the mouse resets the dwell clock.
- **Dwell delay:** How long to hold still before expansion starts.
- **Dwell rate:** How fast the selection grows.
- **Dwell max boost:** Maximum sensitivity increase (caps the expansion).

### Morphological smoothing

Smooths the selection boundary after the flood fill using morphological closing.

- **0:** No smoothing -- raw flood-fill boundary (can look jagged).
- **3--5:** Light smoothing. Default is 5.
- **7--11:** Moderate. Simplifies the boundary.
- **13--15:** Heavy. Very smooth, rounded boundaries.

Use odd values. Even values are rounded up.

### Hole filling

Automatically fills enclosed holes within the selection at the JTS geometry level.

- **Fill holes:** Toggle on/off. Default is on.
- **Min hole size:** Only fill holes smaller than this area in square pixels. Default is 10,000. Set to 0 to fill all holes regardless of size.
- **Alt+draw (subtract mode):** Hole filling is automatically skipped when you hold Alt to erase areas, so deliberately carved holes stay open.

### Connectivity

Controls which pixels are considered neighbors during flood fill.

- **Strict (4-connectivity, default):** Horizontal and vertical neighbors only. More angular selections that won't leak through thin diagonal gaps. Matches the built-in wand.
- **Relaxed (8-connectivity):** Includes diagonal neighbors. Smoother, rounder selections that fill corners better.

### Edge-aware barriers

When enabled, detects intensity edges in the image and prevents the selection from crossing strong boundaries. Adds slight computational overhead.

- **Edge strength (0.0--1.0):** Controls how many edges act as barriers. Low values = only strongest edges; high values = even weak edges block.
- **Edge pyramid level (0--4, default 2):** How much fine detail the edge detector ignores. Higher values sample edges from a coarser pyramid level, making the wand less sensitive to sub-cellular texture when zoomed in. 0 = current zoom level (picks up everything); 2 = 4x coarser (tissue boundaries, not cellular noise).
- **Edge normalization (RELATIVE / ABSOLUTE):** How the edge threshold is computed. RELATIVE (default) adapts to the content in the current window. ABSOLUTE uses a fixed scale, giving consistent barriers across scenes -- recommended when using pyramid level > 0.

### Simplification

Reduces the number of anchor points in the selection for better QuPath performance.

- **Simplify (default 0):** Always-on simplification tolerance. 0 = off (matches built-in wand). Values 0.1--1.0 provide light-to-moderate simplification.
- **Shift+drag:** Holding Shift while drawing applies the aggressive simplification tolerance (default 3.0) for rapid rough annotations with far fewer points.

### Use overlays

When enabled (default), the wand considers painted overlay pixels (e.g., from a pixel classifier) in addition to the raw image. Disable when overlays are distracting or you want to select based purely on the underlying image.

---

## Sensitivity presets

Right-click the toolbar button and choose **Sensitivity presets** for quick bundled settings:

| Preset | Sensitivity | Smoothing | Use case |
|--------|-------------|-----------|----------|
| Fine | 0.3 | 3 | Tight, precise boundaries |
| Standard | 1.0 | 5 | Balanced general use |
| Broad | 2.0 | 7 | Larger uniform regions |
| Aggressive | 4.0 | 9 | Very loose, heavy smoothing |

Each preset sets both sensitivity and morphological smoothing together.

---

## Tune wand from selection

An automated parameter search that finds wand settings matching a reference annotation you draw.

### How to use

1. Zoom to a representative area in the image.
2. Switch to the **polygon** or **brush** tool and draw around what a single wand click should select. This is your ground truth.
3. Leave that annotation selected.
4. Right-click the Wizard Wand toolbar button and choose **Tune wand from selection...**
5. A dialog appears with a progress bar. The search evaluates ~528 parameter combinations.
6. When finished, review the best IoU (Intersection over Union) score and chosen settings.
7. Click **Apply** to use the winning settings, or **Cancel** to keep your original settings.

### What it searches

**Coarse pass (288 candidates):** all combinations of:
- Color space: RGB, GRAY, LAB, HSV
- Sensitivity: 0.1, 0.2, 0.4, 0.7, 1.0, 1.5, 2.5, 4.0
- Sigma: 1.0, 3.0, 6.0
- Smoothing: 0, 5, 11

**Fine pass (240 candidates):** around the coarse winner, also varying:
- Edge-aware on/off
- Strict/relaxed connectivity

### How it works

- A seed point is computed inside your ground-truth annotation using `Geometry.getInteriorPoint()` (guaranteed to lie inside even non-convex shapes).
- For each candidate, the wand pipeline runs at that seed point and the result is scored by IoU against your ground truth.
- Your settings are saved before the search and restored unconditionally afterward (even on cancel or error).
- The search runs on the FX thread using the wand's own instance buffers -- it cannot run while you are mid-stroke (the dialog checks for this).

### Tips

- Draw your ground truth at the zoom level you plan to work at -- the wand's behavior is zoom-dependent.
- Keep the annotation within the wand's 149-pixel sampling window (about 149 x downsample image pixels at the current zoom). Larger annotations may produce lower IoU since the wand physically cannot reach beyond its window.
- An IoU of 0.7+ is a good match; 0.85+ is excellent.
- If no good match is found, try a simpler or smaller reference annotation.

---

## Modifier keys

| Action | While drawing | While not drawing |
|--------|--------------|-------------------|
| Click + drag | Draw / extend selection | -- |
| Click + hold still (300 ms) | Dwell expansion | -- |
| **Shift** + drag | Aggressive simplification | -- |
| **Alt** + click/drag | Subtract from selection (hole filling skipped) | -- |
| **Ctrl** + click | Exact color match (zero threshold, no blur) | -- |
| Right-click toolbar | -- | Context menu (presets, tuning, reset) |

---

## All preferences

All settings are in the QuPath Preferences pane under the **Wizard Wand** category.

| Preference | Type | Default | Description |
|---|---|---|---|
| Color space | Enum | RGB | How pixel colors are compared (RGB, GRAY, LAB_DISTANCE, HSV) |
| Sensitivity | Double | 0.5 | Flood-fill threshold multiplier (higher = bigger selection) |
| Gaussian sigma | Double | 4.0 | Pre-processing blur amount |
| Use overlays | Boolean | true | Include overlay pixels in selection |
| Strict connectivity | Boolean | true | 4-connectivity (checked) vs 8-connectivity (unchecked) |
| Smoothing | Integer | 5 | Morphological closing kernel size (0 = off, odd values) |
| Fill holes | Boolean | true | Auto-fill enclosed holes in selection |
| Min hole size | Integer | 10,000 | Only fill holes smaller than this (px^2, 0 = fill all) |
| Edge-aware | Boolean | false | Enable edge detection barriers |
| Edge strength | Double | 0.5 | How many edges act as barriers (0--1) |
| Edge pyramid level | Integer | 2 | Coarser pyramid level for edge detection (0--4) |
| Edge normalization | Enum | RELATIVE | Edge threshold mode (RELATIVE or ABSOLUTE) |
| Simplify | Double | 0.0 | Always-on geometry simplification tolerance |
| Shift simplify | Double | 3.0 | Aggressive simplification when Shift held |
| Dwell delay (ms) | Double | 300 | Hold-still time before dwell expansion starts |
| Dwell rate | Double | 0.5 | Speed of dwell expansion |
| Dwell max boost | Double | 3.0 | Maximum sensitivity boost from dwell |
| Sensitivity min | Double | 0.05 | Lower bound for effective sensitivity |
| Sensitivity max | Double | 5.0 | Upper bound for effective sensitivity |

---

## How features interact

### Sensitivity + dwell expansion
Dwell adds a temporary boost on top of the base sensitivity. The effective value is `sensitivity + dwellBoost`, clamped to the min/max bounds. The boost resets when you start a new stroke.

### Color space + sensitivity
Sensitivity means the same thing across all four color spaces: higher = bigger selection. The threshold formula (`stddev x sensitivity`) applies to RGB, GRAY, and HSV modes. LAB mode uses `meanDistance x sensitivity` instead, but the direction is the same.

### Edge-aware + edge pyramid level
Edge detection runs on its own captured image patch. When pyramid level > 0, the edge image is captured at a coarser pyramid level than the selection patch, so tissue boundaries survive but cellular texture is blurred away. This makes edge barriers zoom-stable: the same tissue contours act as walls whether you are zoomed in to 40x or out to 5x.

### Edge-aware + sensitivity
Edge barriers and color-threshold barriers are independent. Edge barriers are burned into the flood-fill mask before the fill runs, so they block even if the color threshold would allow crossing. Use edge-aware when sensitivity tuning alone cannot stop the selection from leaking.

### Morphological smoothing + hole filling
Smoothing runs first (on the binary mask), then hole filling runs on the extracted JTS geometry. Heavier smoothing can close small holes before the geometry stage even sees them, so the two features compound. If you need precise hole preservation, use low smoothing.

### Simplify + smoothing
Both reduce vertex count, but at different stages. Smoothing operates on the binary pixel mask (morphological closing). Simplification operates on the extracted polygon vertices (Visvalingam-Whyatt). They are complementary: smoothing gives smoother visual boundaries; simplification reduces file size and improves QuPath performance with many annotations.

### Fill holes + Alt subtract
Alt+draw (subtract mode) deliberately cuts holes. Hole filling is automatically skipped during subtract strokes and on the final commit, so your carved holes persist. Hole filling only runs during additive drawing.

### Tune wand + current settings
The tuner searches wandType, sensitivity, sigma, smoothing, edge-aware, and connectivity. It does NOT change: fill holes, min hole size, overlay usage, simplification, dwell settings, edge strength, edge pyramid level, or edge normalization. Those are held at whatever you currently have set and the tuner optimizes around them.

---

## Building from source

```bash
git clone https://github.com/uw-loci/qupath-extension-wizard-wand.git
cd qupath-extension-wizard-wand
./gradlew shadowJar
```

The JAR is written to `build/libs/qupath-extension-wizard-wand-X.Y.Z-all.jar`.

## License

See [LICENSE](LICENSE).
