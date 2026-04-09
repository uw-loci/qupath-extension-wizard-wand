package qupath.ext.wizardwand;

import javafx.collections.ObservableList;
import org.controlsfx.control.PropertySheet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.prefs.controlsfx.PropertyItemBuilder;
import qupath.lib.gui.QuPathGUI;

/**
 * Registers all Wizard Wand preferences in the QuPath preference pane.
 * Uses PropertyItemBuilder directly (not annotation-based) to avoid
 * requiring a localization resource bundle.
 */
public class WizardWandPreferences {

    private static final Logger logger = LoggerFactory.getLogger(WizardWandPreferences.class);
    private static final String CATEGORY = "Wizard Wand";

    public static void installPreferences(QuPathGUI qupath) {
        if (qupath == null)
            return;

        logger.info("Installing Wizard Wand preferences");

        ObservableList<PropertySheet.Item> items =
                qupath.getPreferencePane()
                        .getPropertySheet()
                        .getItems();

        // --- Core wand settings ---

        items.add(new PropertyItemBuilder<>(WizardWandParameters.wandTypeProperty(), WizardWandType.class)
                .name("Color space")
                .category(CATEGORY)
                .description("How pixel colors are compared when growing a selection.\n\n"
                        + "RGB: Standard color comparison. Good general-purpose default.\n"
                        + "GRAY: Ignores color, uses brightness only. Best for grayscale or H&E images "
                        + "where you want to select by stain intensity.\n"
                        + "LAB_DISTANCE: Perceptual color distance. Better than RGB when similar-looking "
                        + "colors have different RGB values (e.g., distinguishing subtle stain differences).\n"
                        + "HSV: Hue-saturation-value. Good when you want to select by color hue regardless "
                        + "of brightness (e.g., selecting all blue nuclei even if some are darker).")
                .build());

        items.add(new PropertyItemBuilder<>(WizardWandParameters.sensitivityProperty(), Double.class)
                .name("Sensitivity")
                .category(CATEGORY)
                .description("Controls how large the wand selection grows. "
                        + "Higher values = bigger selection (consistent across all color modes).\n\n"
                        + "Low values (0.1-0.5): Tight, precise selections. Use when tissue "
                        + "boundaries are well-defined and you want to avoid leaking.\n"
                        + "Medium values (0.5-1.5): Balanced. Default is 1.0, slightly more "
                        + "generous than the built-in wand.\n"
                        + "High values (2.0+): Expansive selection. Use for quickly annotating "
                        + "large, uniform regions like stroma or background.\n\n"
                        + "Tip: Scroll the mouse wheel while drawing to adjust this in real-time.")
                .build());

        items.add(new PropertyItemBuilder<>(WizardWandParameters.sigmaProperty(), Double.class)
                .name("Gaussian sigma")
                .category(CATEGORY)
                .description("Amount of blur applied before the wand evaluates pixel similarity.\n\n"
                        + "Low values (0.5-2.0): Preserves fine detail. Selection boundaries follow "
                        + "individual pixel edges. Can be noisy.\n"
                        + "Medium values (3.0-5.0): Smooths out noise while keeping major boundaries. "
                        + "Good default for most images.\n"
                        + "High values (6.0+): Heavy smoothing. Selection ignores small features and "
                        + "follows only broad tissue boundaries. Use when the image is noisy or you "
                        + "want to select large uniform regions.")
                .build());

        items.add(new PropertyItemBuilder<>(WizardWandParameters.useOverlaysProperty(), Boolean.class)
                .name("Use overlays")
                .category(CATEGORY)
                .description("When enabled, the wand considers painted overlay pixels (e.g., from a "
                        + "pixel classifier) in addition to the raw image when computing the selection.\n\n"
                        + "Enable when you want the wand to follow classification boundaries.\n"
                        + "Disable when overlays are distracting or you want to select based purely "
                        + "on the underlying image.")
                .build());

        // --- Selection refinement ---

        items.add(new PropertyItemBuilder<>(WizardWandParameters.strictConnectivityProperty(), Boolean.class)
                .name("Strict connectivity")
                .category(CATEGORY)
                .description("When checked, uses 4-connectivity (horizontal/vertical neighbors only). "
                        + "Produces more angular selections that won't leak through thin diagonal gaps. "
                        + "Use when structures have thin boundaries the selection keeps crossing.\n\n"
                        + "When unchecked (default), uses 8-connectivity which also includes diagonal "
                        + "neighbors. Produces smoother, rounder selections that fill corners better.")
                .build());

        items.add(new PropertyItemBuilder<>(WizardWandParameters.morphKernelSizeProperty(), Integer.class)
                .name("Smoothing")
                .category(CATEGORY)
                .description("Smooths the selection boundary after flood fill (morphological closing). "
                        + "Use odd values.\n\n"
                        + "0: No smoothing. Raw flood fill boundary. Can look jagged.\n"
                        + "3-5: Light smoothing. Fills tiny gaps and softens sharp corners. Good default.\n"
                        + "7-11: Moderate smoothing. Simplifies the boundary. Use when the selection "
                        + "is too spiky or has too many small indentations.\n"
                        + "13-15: Heavy smoothing. Produces very smooth, rounded boundaries. Use for "
                        + "broad tissue regions where precision is less important than a clean outline.")
                .build());

        items.add(new PropertyItemBuilder<>(WizardWandParameters.fillHolesProperty(), Boolean.class)
                .name("Fill holes")
                .category(CATEGORY)
                .description("Automatically fills enclosed holes within the wand selection.\n\n"
                        + "Enable (recommended) when annotating solid tissue regions where internal "
                        + "gaps are artifacts of the flood fill algorithm.\n"
                        + "Disable when the holes represent real structures you want to exclude "
                        + "(e.g., lumens, vessels, or clearings within tissue).")
                .build());

        items.add(new PropertyItemBuilder<>(WizardWandParameters.minHoleSizeProperty(), Integer.class)
                .name("Min hole size")
                .category(CATEGORY)
                .description("Only fill holes smaller than this area (in square pixels). "
                        + "Set to 0 to fill ALL holes regardless of size.\n\n"
                        + "Use a small value (50-200) to fill noise artifacts while preserving "
                        + "real structures like lumens.\n"
                        + "Use a large value (1000+) to fill most internal gaps.")
                .build());

        // --- Edge-aware ---

        items.add(new PropertyItemBuilder<>(WizardWandParameters.edgeAwareProperty(), Boolean.class)
                .name("Edge-aware")
                .category(CATEGORY)
                .description("Detects intensity edges in the image and prevents the selection from "
                        + "crossing strong boundaries.\n\n"
                        + "Enable when the wand keeps leaking into adjacent structures despite "
                        + "adjusting sensitivity. The edge detection adds an extra barrier at "
                        + "tissue boundaries.\n"
                        + "Disable (default) for standard wand behavior. Edge detection adds slight "
                        + "computational overhead.")
                .build());

        items.add(new PropertyItemBuilder<>(WizardWandParameters.edgeStrengthProperty(), Double.class)
                .name("Edge strength")
                .category(CATEGORY)
                .description("Controls how many edges act as barriers (when edge-aware is on).\n\n"
                        + "Low values (0.1-0.3): Only the strongest edges block the selection. "
                        + "Use when you want to cross minor boundaries but stop at major ones.\n"
                        + "Medium values (0.4-0.6): Moderate edges and above block. Good default.\n"
                        + "High values (0.7-1.0): Even weak edges act as barriers. Use when tissue "
                        + "boundaries are subtle (e.g., low-contrast stains) and you need the "
                        + "selection to follow them closely.")
                .build());

        // --- Simplification ---

        items.add(new PropertyItemBuilder<>(WizardWandParameters.simplifyToleranceProperty(), Double.class)
                .name("Simplify")
                .category(CATEGORY)
                .description("Automatically reduces anchor points in the selection to improve performance.\n\n"
                        + "Low values (0.1-0.3): Minimal simplification. More precise boundaries but "
                        + "more anchor points, which can slow down QuPath with many annotations.\n"
                        + "Medium values (0.5-1.0): Good balance of precision and performance.\n"
                        + "High values (2.0+): Aggressive simplification. Use if QuPath is slow due "
                        + "to annotations with too many vertices.")
                .build());

        items.add(new PropertyItemBuilder<>(WizardWandParameters.aggressiveSimplifyToleranceProperty(), Double.class)
                .name("Shift simplify")
                .category(CATEGORY)
                .description("Simplification tolerance applied when holding Shift while drawing. "
                        + "Use for rapid rough annotations with far fewer anchor points.\n\n"
                        + "Values of 2.0-5.0 work well. Higher values produce simpler shapes.\n"
                        + "Useful when you need quick region outlines and will refine later, "
                        + "or when annotating large areas where precision is less important.")
                .build());

        // --- Interaction tuning ---

        items.add(new PropertyItemBuilder<>(WizardWandParameters.dwellDelayProperty(), Double.class)
                .name("Dwell delay (ms)")
                .category(CATEGORY)
                .description("How long to hold the mouse still (in milliseconds) before dwell "
                        + "expansion starts growing the selection.\n\n"
                        + "Lower values (100-200): Expansion starts quickly. Can trigger accidentally.\n"
                        + "Default (300): Good balance -- deliberate pauses trigger expansion, "
                        + "brief hesitations during drawing do not.\n"
                        + "Higher values (500+): More deliberate hold needed. Less accidental triggering.")
                .build());

        items.add(new PropertyItemBuilder<>(WizardWandParameters.dwellExpansionRateProperty(), Double.class)
                .name("Dwell rate")
                .category(CATEGORY)
                .description("How fast the selection grows during dwell expansion.\n\n"
                        + "Low values (0.1-0.3): Slow, controlled expansion. Easier to stop at the "
                        + "right boundary.\n"
                        + "Default (0.5): Moderate growth.\n"
                        + "High values (1.0+): Fast expansion. Use for quickly filling large regions.")
                .build());

        items.add(new PropertyItemBuilder<>(WizardWandParameters.dwellMaxBoostProperty(), Double.class)
                .name("Dwell max boost")
                .category(CATEGORY)
                .description("Maximum sensitivity increase from dwell expansion. Caps how far "
                        + "the selection can grow by holding still.\n\n"
                        + "Default (10.0): Allows substantial expansion.\n"
                        + "Lower values (3.0-5.0): Limits expansion for finer control.")
                .build());

        items.add(new PropertyItemBuilder<>(WizardWandParameters.scrollSensitivityStepProperty(), Double.class)
                .name("Scroll factor")
                .category(CATEGORY)
                .description("Relative change per scroll tick (multiplicative). Each tick "
                        + "multiplies or divides sensitivity by (1 + this value).\n\n"
                        + "0.05: 5% change per tick. Very fine control.\n"
                        + "0.15: 15% change per tick. Default -- balanced.\n"
                        + "0.30: 30% change per tick. Coarser, faster adjustment.\n\n"
                        + "Multiplicative scrolling gives consistent relative changes "
                        + "regardless of the current sensitivity, so small values near "
                        + "the interesting range still get fine control.")
                .build());

        items.add(new PropertyItemBuilder<>(WizardWandParameters.sensitivityMinProperty(), Double.class)
                .name("Sensitivity min")
                .category(CATEGORY)
                .description("Lower bound for sensitivity. Scroll wheel and dwell cannot go below this.\n"
                        + "Default: 0.25")
                .build());

        items.add(new PropertyItemBuilder<>(WizardWandParameters.sensitivityMaxProperty(), Double.class)
                .name("Sensitivity max")
                .category(CATEGORY)
                .description("Upper bound for sensitivity. Scroll wheel and dwell cannot exceed this.\n"
                        + "Default: 15.0")
                .build());

        logger.info("Wizard Wand preferences installed ({} items)", 18);
    }
}
