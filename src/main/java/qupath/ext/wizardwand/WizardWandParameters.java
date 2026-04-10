package qupath.ext.wizardwand;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleDoubleProperty; // Used for transient dwellSensitivityBoost
import qupath.lib.gui.prefs.PathPrefs;

/**
 * All configurable parameters for the Wizard Wand tool.
 * Persisted parameters survive QuPath restarts via PathPrefs.
 * Session-only parameters reset to defaults on each launch.
 */
public class WizardWandParameters {

    // --- Persisted preferences ---

    private static final ObjectProperty<WizardWandType> wandType =
            PathPrefs.createPersistentPreference("wizardWandType", WizardWandType.RGB, WizardWandType.class);

    // Formula: threshold = stddev * sensitivity.
    // Default 0.5 matches the built-in wand (which uses stddev / 2.0 at its
    // default sensitivity of 2.0). Key versioned to force new default.
    private static final DoubleProperty sensitivity =
            PathPrefs.createPersistentPreference("wizardWandSensitivityV4", 0.5);

    private static final DoubleProperty sigma =
            PathPrefs.createPersistentPreference("wizardWandSigma", 4.0);

    private static final BooleanProperty useOverlays =
            PathPrefs.createPersistentPreference("wizardWandUseOverlays", true);

    // true = strict 4-connectivity (edges only, matches built-in wand), false = relaxed 8-connectivity
    private static final BooleanProperty strictConnectivity =
            PathPrefs.createPersistentPreference("wizardWandStrictConnectivityV2", true);

    private static final IntegerProperty morphKernelSize =
            PathPrefs.createPersistentPreference("wizardWandMorphKernelSize", 5);

    private static final BooleanProperty fillHoles =
            PathPrefs.createPersistentPreference("wizardWandFillHoles", true);

    private static final IntegerProperty minHoleSize =
            PathPrefs.createPersistentPreference("wizardWandMinHoleSizeV2", 10000);

    private static final BooleanProperty edgeAware =
            PathPrefs.createPersistentPreference("wizardWandEdgeAware", false);

    private static final DoubleProperty edgeStrength =
            PathPrefs.createPersistentPreference("wizardWandEdgeStrength", 0.5);

    // How many pyramid levels coarser to run edge detection. 0 = same level as
    // the view, 1 = 2x coarser, 2 = 4x coarser, etc. Higher values give more
    // stable, structural edges that don't collapse into cellular texture when
    // the user zooms in. Key changed to force the new default on users who
    // already had the pref set to 0 from the previous release.
    private static final IntegerProperty edgePyramidOffset =
            PathPrefs.createPersistentPreference("wizardWandEdgePyramidOffsetV2", 2);

    // How edge barriers are thresholded. RELATIVE (default, backward-compatible)
    // uses (1 - edgeStrength) * maxGradient. ABSOLUTE uses (1 - edgeStrength) * 255
    // on a fixed-range gradient, so the slider value is zoom/content-stable.
    private static final ObjectProperty<EdgeNormalizationMode> edgeNormalizationMode =
            PathPrefs.createPersistentPreference("wizardWandEdgeNormalizationMode",
                    EdgeNormalizationMode.RELATIVE, EdgeNormalizationMode.class);

    // 0 = no always-on simplification (matches built-in wand). Shift-mode
    // aggressive simplification still applies when held.
    private static final DoubleProperty simplifyTolerance =
            PathPrefs.createPersistentPreference("wizardWandSimplifyToleranceV2", 0.0);

    private static final DoubleProperty aggressiveSimplifyTolerance =
            PathPrefs.createPersistentPreference("wizardWandAggressiveSimplifyTolerance", 3.0);

    // --- Interaction tuning (persisted) ---

    private static final DoubleProperty dwellDelay =
            PathPrefs.createPersistentPreference("wizardWandDwellDelay", 300.0);

    private static final DoubleProperty dwellExpansionRate =
            PathPrefs.createPersistentPreference("wizardWandDwellExpansionRate", 0.5);

    private static final DoubleProperty dwellMaxBoost =
            PathPrefs.createPersistentPreference("wizardWandDwellMaxBoostV2", 3.0);

    private static final DoubleProperty sensitivityMin =
            PathPrefs.createPersistentPreference("wizardWandSensitivityMinV2", 0.05);

    private static final DoubleProperty sensitivityMax =
            PathPrefs.createPersistentPreference("wizardWandSensitivityMaxV2", 5.0);

    // --- Transient state (reset per drawing operation, not persisted) ---

    private static final DoubleProperty dwellSensitivityBoost = new SimpleDoubleProperty(0.0);

    // --- Property accessors ---

    public static ObjectProperty<WizardWandType> wandTypeProperty() { return wandType; }
    public static WizardWandType getWandType() { return wandType.get(); }
    public static void setWandType(WizardWandType type) { wandType.set(type); }

    public static DoubleProperty sensitivityProperty() { return sensitivity; }
    public static double getSensitivity() { return sensitivity.get(); }
    public static void setSensitivity(double value) { sensitivity.set(value); }

    public static DoubleProperty sigmaProperty() { return sigma; }
    public static double getSigma() { return sigma.get(); }

    public static BooleanProperty useOverlaysProperty() { return useOverlays; }
    public static boolean getUseOverlays() { return useOverlays.get(); }

    public static BooleanProperty strictConnectivityProperty() { return strictConnectivity; }
    public static boolean getStrictConnectivity() { return strictConnectivity.get(); }
    /** Returns 4 for strict connectivity, 8 for relaxed (default). */
    public static int getConnectivity() { return strictConnectivity.get() ? 4 : 8; }

    public static IntegerProperty morphKernelSizeProperty() { return morphKernelSize; }
    public static int getMorphKernelSize() { return morphKernelSize.get(); }

    public static BooleanProperty fillHolesProperty() { return fillHoles; }
    public static boolean getFillHoles() { return fillHoles.get(); }

    public static IntegerProperty minHoleSizeProperty() { return minHoleSize; }
    public static int getMinHoleSize() { return minHoleSize.get(); }

    public static BooleanProperty edgeAwareProperty() { return edgeAware; }
    public static boolean getEdgeAware() { return edgeAware.get(); }

    public static DoubleProperty edgeStrengthProperty() { return edgeStrength; }
    public static double getEdgeStrength() { return edgeStrength.get(); }

    public static IntegerProperty edgePyramidOffsetProperty() { return edgePyramidOffset; }
    public static int getEdgePyramidOffset() {
        return Math.max(0, Math.min(4, edgePyramidOffset.get()));
    }

    public static ObjectProperty<EdgeNormalizationMode> edgeNormalizationModeProperty() {
        return edgeNormalizationMode;
    }
    public static EdgeNormalizationMode getEdgeNormalizationMode() {
        return edgeNormalizationMode.get();
    }

    public static DoubleProperty simplifyToleranceProperty() { return simplifyTolerance; }
    public static double getSimplifyTolerance() { return simplifyTolerance.get(); }

    public static DoubleProperty aggressiveSimplifyToleranceProperty() { return aggressiveSimplifyTolerance; }
    public static double getAggressiveSimplifyTolerance() { return aggressiveSimplifyTolerance.get(); }

    public static DoubleProperty dwellDelayProperty() { return dwellDelay; }
    public static double getDwellDelay() { return dwellDelay.get(); }

    public static DoubleProperty dwellExpansionRateProperty() { return dwellExpansionRate; }
    public static double getDwellExpansionRate() { return dwellExpansionRate.get(); }

    public static DoubleProperty dwellMaxBoostProperty() { return dwellMaxBoost; }
    public static double getDwellMaxBoost() { return dwellMaxBoost.get(); }

    public static DoubleProperty sensitivityMinProperty() { return sensitivityMin; }
    public static double getSensitivityMin() { return sensitivityMin.get(); }

    public static DoubleProperty sensitivityMaxProperty() { return sensitivityMax; }
    public static double getSensitivityMax() { return sensitivityMax.get(); }

    public static DoubleProperty dwellSensitivityBoostProperty() { return dwellSensitivityBoost; }
    public static double getDwellSensitivityBoost() { return dwellSensitivityBoost.get(); }
    public static void setDwellSensitivityBoost(double value) { dwellSensitivityBoost.set(value); }

    /**
     * Get the effective sensitivity (base + dwell boost), clamped to valid range.
     */
    public static double getEffectiveSensitivity() {
        return Math.max(getSensitivityMin(),
                Math.min(getSensitivityMax(), getSensitivity() + getDwellSensitivityBoost()));
    }

    /**
     * Apply a sensitivity preset, updating sensitivity and closing size.
     */
    public static void applyPreset(SensitivityPreset preset) {
        setSensitivity(preset.getSensitivity());
        morphKernelSize.set(preset.getClosingSize());
    }

    /**
     * Reset all Wizard Wand preferences to their default values.
     * Does not affect any other QuPath preferences.
     */
    public static void resetDefaults() {
        wandType.set(WizardWandType.RGB);
        sensitivity.set(0.5);
        sigma.set(4.0);
        useOverlays.set(true);
        strictConnectivity.set(true);
        morphKernelSize.set(5);
        fillHoles.set(true);
        minHoleSize.set(10000);
        edgeAware.set(false);
        edgeStrength.set(0.5);
        edgePyramidOffset.set(2);
        edgeNormalizationMode.set(EdgeNormalizationMode.RELATIVE);
        simplifyTolerance.set(0.0);
        aggressiveSimplifyTolerance.set(3.0);
        dwellDelay.set(300.0);
        dwellExpansionRate.set(0.5);
        dwellMaxBoost.set(3.0);
        sensitivityMin.set(0.05);
        sensitivityMax.set(5.0);
        dwellSensitivityBoost.set(0.0);
    }
}
