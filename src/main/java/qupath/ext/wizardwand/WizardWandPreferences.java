package qupath.ext.wizardwand;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import qupath.fx.prefs.annotations.BooleanPref;
import qupath.fx.prefs.annotations.DoublePref;
import qupath.fx.prefs.annotations.IntegerPref;
import qupath.fx.prefs.annotations.Pref;
import qupath.fx.prefs.annotations.PrefCategory;

/**
 * Preference definitions for the Wizard Wand tool.
 * Uses annotation-based system for automatic preference pane integration.
 */
@PrefCategory("Wizard Wand")
public class WizardWandPreferences {

    @Pref(value = "Color space", type = WizardWandType.class)
    public final ObjectProperty<WizardWandType> wandType = WizardWandParameters.wandTypeProperty();

    @DoublePref("Sensitivity")
    public final DoubleProperty sensitivity = WizardWandParameters.sensitivityProperty();

    @DoublePref("Gaussian sigma")
    public final DoubleProperty sigma = WizardWandParameters.sigmaProperty();

    @BooleanPref("Use overlays")
    public final BooleanProperty useOverlays = WizardWandParameters.useOverlaysProperty();

    @IntegerPref("Connectivity (4=strict, 8=relaxed)")
    public final IntegerProperty connectivity = WizardWandParameters.connectivityProperty();

    @IntegerPref("Selection smoothing (kernel size, 0=off)")
    public final IntegerProperty morphKernelSize = WizardWandParameters.morphKernelSizeProperty();

    @BooleanPref("Fill holes in selection")
    public final BooleanProperty fillHoles = WizardWandParameters.fillHolesProperty();

    @IntegerPref("Minimum hole size (px^2, 0=fill all)")
    public final IntegerProperty minHoleSize = WizardWandParameters.minHoleSizeProperty();

    @BooleanPref("Edge-aware selection")
    public final BooleanProperty edgeAware = WizardWandParameters.edgeAwareProperty();

    @DoublePref("Edge strength threshold (0-1)")
    public final DoubleProperty edgeStrength = WizardWandParameters.edgeStrengthProperty();

    @DoublePref("Simplification tolerance")
    public final DoubleProperty simplifyTolerance = WizardWandParameters.simplifyToleranceProperty();

    @DoublePref("Aggressive simplification tolerance (Shift)")
    public final DoubleProperty aggressiveSimplifyTolerance = WizardWandParameters.aggressiveSimplifyToleranceProperty();
}
