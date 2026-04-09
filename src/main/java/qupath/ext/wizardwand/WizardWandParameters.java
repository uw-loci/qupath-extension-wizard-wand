package qupath.ext.wizardwand;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleDoubleProperty;
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

    private static final DoubleProperty sensitivity =
            PathPrefs.createPersistentPreference("wizardWandSensitivity", 2.0);

    private static final DoubleProperty sigma =
            PathPrefs.createPersistentPreference("wizardWandSigma", 4.0);

    private static final BooleanProperty useOverlays =
            PathPrefs.createPersistentPreference("wizardWandUseOverlays", true);

    private static final IntegerProperty connectivity =
            PathPrefs.createPersistentPreference("wizardWandConnectivity", 4);

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

    // --- Session-only properties (reset on restart) ---

    private static final DoubleProperty dwellDelay = new SimpleDoubleProperty(300.0);
    private static final DoubleProperty dwellExpansionRate = new SimpleDoubleProperty(0.5);
    private static final DoubleProperty scrollSensitivityStep = new SimpleDoubleProperty(0.25);

    // --- Transient state (reset per drawing operation) ---

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

    public static IntegerProperty connectivityProperty() { return connectivity; }
    public static int getConnectivity() { return connectivity.get(); }

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

    public static DoubleProperty scrollSensitivityStepProperty() { return scrollSensitivityStep; }
    public static double getScrollSensitivityStep() { return scrollSensitivityStep.get(); }

    public static DoubleProperty dwellSensitivityBoostProperty() { return dwellSensitivityBoost; }
    public static double getDwellSensitivityBoost() { return dwellSensitivityBoost.get(); }
    public static void setDwellSensitivityBoost(double value) { dwellSensitivityBoost.set(value); }

    /**
     * Get the effective sensitivity (base + dwell boost), clamped to valid range.
     */
    public static double getEffectiveSensitivity() {
        return Math.max(0.25, Math.min(15.0, getSensitivity() + getDwellSensitivityBoost()));
    }

    /**
     * Adjust sensitivity by a delta amount (from scroll wheel).
     * Clamps to [0.25, 15.0].
     */
    public static void adjustSensitivity(double delta) {
        double current = getSensitivity();
        double step = getScrollSensitivityStep();
        double newValue = Math.max(0.25, Math.min(15.0, current + delta * step));
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
