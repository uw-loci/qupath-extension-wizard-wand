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

    // Key changed from "wizardWandSensitivity" to force new default after formula change.
    // Formula: threshold = stddev * sensitivity.
    // Default 0.6 is in the middle of the typically-useful range (0.4-1.0) based
    // on testing. Users can scroll-wheel to adjust while drawing.
    private static final DoubleProperty sensitivity =
            PathPrefs.createPersistentPreference("wizardWandSensitivityV3", 0.6);

    private static final DoubleProperty sigma =
            PathPrefs.createPersistentPreference("wizardWandSigma", 4.0);

    private static final BooleanProperty useOverlays =
            PathPrefs.createPersistentPreference("wizardWandUseOverlays", true);

    // true = strict 4-connectivity (edges only), false = relaxed 8-connectivity (default, includes diagonals)
    private static final BooleanProperty strictConnectivity =
            PathPrefs.createPersistentPreference("wizardWandStrictConnectivity", false);

    private static final IntegerProperty morphKernelSize =
            PathPrefs.createPersistentPreference("wizardWandMorphKernelSize", 5);

    private static final BooleanProperty fillHoles =
            PathPrefs.createPersistentPreference("wizardWandFillHoles", true);

    private static final IntegerProperty minHoleSize =
            PathPrefs.createPersistentPreference("wizardWandMinHoleSize", 100);

    private static final BooleanProperty edgeAware =
            PathPrefs.createPersistentPreference("wizardWandEdgeAware", false);

    private static final DoubleProperty edgeStrength =
            PathPrefs.createPersistentPreference("wizardWandEdgeStrength", 0.5);

    private static final DoubleProperty simplifyTolerance =
            PathPrefs.createPersistentPreference("wizardWandSimplifyTolerance", 0.5);

    private static final DoubleProperty aggressiveSimplifyTolerance =
            PathPrefs.createPersistentPreference("wizardWandAggressiveSimplifyTolerance", 3.0);

    // --- Interaction tuning (persisted) ---

    private static final DoubleProperty dwellDelay =
            PathPrefs.createPersistentPreference("wizardWandDwellDelay", 300.0);

    private static final DoubleProperty dwellExpansionRate =
            PathPrefs.createPersistentPreference("wizardWandDwellExpansionRate", 0.5);

    private static final DoubleProperty dwellMaxBoost =
            PathPrefs.createPersistentPreference("wizardWandDwellMaxBoostV2", 3.0);

    // Interpreted as a multiplicative factor: 0.15 = 15% change per scroll tick
    // (newValue = current * 1.15 up, or current / 1.15 down)
    private static final DoubleProperty scrollSensitivityStep =
            PathPrefs.createPersistentPreference("wizardWandScrollFactorV3", 0.15);

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

    public static DoubleProperty scrollSensitivityStepProperty() { return scrollSensitivityStep; }
    public static double getScrollSensitivityStep() { return scrollSensitivityStep.get(); }

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
     * Adjust sensitivity by a scroll tick.
     * Uses multiplicative adjustment so each tick produces a consistent
     * relative change regardless of the current sensitivity value.
     * <p>
     * scrollSensitivityStep is interpreted as a fractional factor. For
     * example, 0.15 means each tick changes by 15% (multiply or divide
     * by 1.15). This gives intuitive fine control across the full range.
     */
    public static void adjustSensitivity(double delta) {
        double current = getSensitivity();
        double factor = 1.0 + Math.abs(getScrollSensitivityStep());
        double newValue;
        if (delta > 0) {
            newValue = current * factor;
        } else {
            newValue = current / factor;
        }
        newValue = Math.max(getSensitivityMin(),
                Math.min(getSensitivityMax(), newValue));
        setSensitivity(newValue);
    }

    /**
     * Apply a sensitivity preset, updating sensitivity and closing size.
     */
    public static void applyPreset(SensitivityPreset preset) {
        setSensitivity(preset.getSensitivity());
        morphKernelSize.set(preset.getClosingSize());
    }
}
