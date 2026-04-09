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
                .description("Color space used for pixel similarity matching.")
                .build());

        items.add(new PropertyItemBuilder<>(WizardWandParameters.sensitivityProperty(), Double.class)
                .name("Sensitivity")
                .category(CATEGORY)
                .description("How similar pixels must be to the seed point. "
                        + "Higher values select larger regions.")
                .build());

        items.add(new PropertyItemBuilder<>(WizardWandParameters.sigmaProperty(), Double.class)
                .name("Gaussian sigma")
                .category(CATEGORY)
                .description("Gaussian blur applied before flood fill. "
                        + "Higher values smooth out noise but reduce precision.")
                .build());

        items.add(new PropertyItemBuilder<>(WizardWandParameters.useOverlaysProperty(), Boolean.class)
                .name("Use overlays")
                .category(CATEGORY)
                .description("Include overlay pixel values when computing wand selection.")
                .build());

        // --- Selection refinement ---

        items.add(new PropertyItemBuilder<>(WizardWandParameters.connectivityProperty(), Integer.class)
                .name("Connectivity (4=strict, 8=relaxed)")
                .category(CATEGORY)
                .description("4-connectivity uses only horizontal/vertical neighbors. "
                        + "8-connectivity also includes diagonals.")
                .build());

        items.add(new PropertyItemBuilder<>(WizardWandParameters.morphKernelSizeProperty(), Integer.class)
                .name("Selection smoothing (kernel size, 0=off)")
                .category(CATEGORY)
                .description("Morphological closing kernel size to smooth selection edges. "
                        + "Use odd values 1-15. Set to 0 to disable.")
                .build());

        items.add(new PropertyItemBuilder<>(WizardWandParameters.fillHolesProperty(), Boolean.class)
                .name("Fill holes in selection")
                .category(CATEGORY)
                .description("Automatically fill enclosed holes in the wand selection.")
                .build());

        items.add(new PropertyItemBuilder<>(WizardWandParameters.minHoleSizeProperty(), Integer.class)
                .name("Minimum hole size (px^2, 0=fill all)")
                .category(CATEGORY)
                .description("Only fill holes smaller than this area. Set to 0 to fill all holes.")
                .build());

        // --- Edge-aware ---

        items.add(new PropertyItemBuilder<>(WizardWandParameters.edgeAwareProperty(), Boolean.class)
                .name("Edge-aware selection")
                .category(CATEGORY)
                .description("Use gradient detection to prevent selection from crossing strong edges.")
                .build());

        items.add(new PropertyItemBuilder<>(WizardWandParameters.edgeStrengthProperty(), Double.class)
                .name("Edge strength threshold (0-1)")
                .category(CATEGORY)
                .description("How strong an edge must be to block selection. "
                        + "Lower values detect weaker edges.")
                .build());

        // --- Simplification ---

        items.add(new PropertyItemBuilder<>(WizardWandParameters.simplifyToleranceProperty(), Double.class)
                .name("Simplification tolerance")
                .category(CATEGORY)
                .description("Always-on geometry simplification to reduce anchor points. "
                        + "Higher values produce smoother, simpler shapes.")
                .build());

        items.add(new PropertyItemBuilder<>(WizardWandParameters.aggressiveSimplifyToleranceProperty(), Double.class)
                .name("Aggressive simplification tolerance (Shift)")
                .category(CATEGORY)
                .description("Simplification tolerance when holding Shift during drawing. "
                        + "Use for rapid rough annotations with fewer points.")
                .build());

        // --- Interaction tuning ---

        items.add(new PropertyItemBuilder<>(WizardWandParameters.dwellDelayProperty(), Double.class)
                .name("Dwell delay before expansion (ms)")
                .category(CATEGORY)
                .description("How long to hold still before dwell expansion starts.")
                .build());

        items.add(new PropertyItemBuilder<>(WizardWandParameters.dwellExpansionRateProperty(), Double.class)
                .name("Dwell expansion rate")
                .category(CATEGORY)
                .description("Speed of dwell expansion. Higher values expand faster.")
                .build());

        items.add(new PropertyItemBuilder<>(WizardWandParameters.dwellMaxBoostProperty(), Double.class)
                .name("Dwell max sensitivity boost")
                .category(CATEGORY)
                .description("Maximum sensitivity boost from dwell expansion.")
                .build());

        items.add(new PropertyItemBuilder<>(WizardWandParameters.scrollSensitivityStepProperty(), Double.class)
                .name("Scroll wheel sensitivity step")
                .category(CATEGORY)
                .description("How much each scroll tick changes the sensitivity value.")
                .build());

        items.add(new PropertyItemBuilder<>(WizardWandParameters.sensitivityMinProperty(), Double.class)
                .name("Minimum sensitivity")
                .category(CATEGORY)
                .description("Lower bound for sensitivity (scroll/dwell cannot go below this).")
                .build());

        items.add(new PropertyItemBuilder<>(WizardWandParameters.sensitivityMaxProperty(), Double.class)
                .name("Maximum sensitivity")
                .category(CATEGORY)
                .description("Upper bound for sensitivity (scroll/dwell cannot exceed this).")
                .build());

        logger.info("Wizard Wand preferences installed ({} items)", 18);
    }
}
