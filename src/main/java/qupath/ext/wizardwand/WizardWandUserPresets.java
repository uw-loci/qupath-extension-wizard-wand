package qupath.ext.wizardwand;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.prefs.PathPrefs;

import javafx.beans.property.StringProperty;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages user-defined wand presets. Presets are stored as a JSON array in a
 * single PathPrefs string preference, separate from the main wand preferences
 * so they survive "Reset Wizard Wand preferences."
 */
public final class WizardWandUserPresets {

    private static final Logger logger = LoggerFactory.getLogger(WizardWandUserPresets.class);

    private static final StringProperty storage =
            PathPrefs.createPersistentPreference("wizardWandUserPresets", "[]");

    private static final Gson gson = new GsonBuilder().create();
    private static final Type LIST_TYPE = new TypeToken<List<UserPreset>>() {}.getType();

    /**
     * A saved wand configuration. Captures all persistent wand settings
     * including dwell timing, fixed downsample, and the enhanced handler toggle.
     */
    public record UserPreset(
            String name,
            // --- Wand behavior ---
            WizardWandType wandType,
            double sensitivity,
            double sigma,
            boolean useOverlays,
            boolean strictConnectivity,
            int morphKernelSize,
            boolean fillHoles,
            int minHoleSize,
            boolean edgeAware,
            double edgeStrength,
            int edgePyramidOffset,
            EdgeNormalizationMode edgeNormalizationMode,
            double simplifyTolerance,
            double aggressiveSimplifyTolerance,
            // --- Dwell timing ---
            double dwellDelay,
            double dwellExpansionRate,
            double dwellMaxBoost,
            // --- Fixed downsample ---
            boolean fixedDownsample,
            double fixedDownsampleLevel,
            // --- Enhanced handler ---
            boolean useEnhancedBrushHandler
    ) {}

    /** Load all user presets from the persistent preference. */
    public static List<UserPreset> loadPresets() {
        try {
            String json = storage.get();
            if (json == null || json.isBlank())
                return new ArrayList<>();
            List<UserPreset> list = gson.fromJson(json, LIST_TYPE);
            return list != null ? new ArrayList<>(list) : new ArrayList<>();
        } catch (Exception e) {
            logger.warn("Failed to load user presets: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** Save the list of user presets to the persistent preference. */
    public static void savePresets(List<UserPreset> presets) {
        try {
            storage.set(gson.toJson(presets, LIST_TYPE));
        } catch (Exception e) {
            logger.error("Failed to save user presets: {}", e.getMessage());
        }
    }

    /** Capture the current WizardWandParameters state as a UserPreset with the given name. */
    public static UserPreset captureCurrentAsPreset(String name) {
        return new UserPreset(
                name,
                WizardWandParameters.getWandType(),
                WizardWandParameters.getSensitivity(),
                WizardWandParameters.getSigma(),
                WizardWandParameters.getUseOverlays(),
                WizardWandParameters.getStrictConnectivity(),
                WizardWandParameters.getMorphKernelSize(),
                WizardWandParameters.getFillHoles(),
                WizardWandParameters.getMinHoleSize(),
                WizardWandParameters.getEdgeAware(),
                WizardWandParameters.getEdgeStrength(),
                WizardWandParameters.getEdgePyramidOffset(),
                WizardWandParameters.getEdgeNormalizationMode(),
                WizardWandParameters.getSimplifyTolerance(),
                WizardWandParameters.getAggressiveSimplifyTolerance(),
                WizardWandParameters.getDwellDelay(),
                WizardWandParameters.getDwellExpansionRate(),
                WizardWandParameters.getDwellMaxBoost(),
                WizardWandParameters.getFixedDownsample(),
                WizardWandParameters.getFixedDownsampleLevel(),
                WizardWandParameters.getUseEnhancedBrushHandler()
        );
    }

    /** Apply a UserPreset to the current WizardWandParameters. */
    public static void applyPreset(UserPreset p) {
        WizardWandParameters.setWandType(p.wandType());
        WizardWandParameters.setSensitivity(p.sensitivity());
        WizardWandParameters.setSigma(p.sigma());
        WizardWandParameters.useOverlaysProperty().set(p.useOverlays());
        WizardWandParameters.setStrictConnectivity(p.strictConnectivity());
        WizardWandParameters.setMorphKernelSize(p.morphKernelSize());
        WizardWandParameters.setFillHoles(p.fillHoles());
        WizardWandParameters.minHoleSizeProperty().set(p.minHoleSize());
        WizardWandParameters.setEdgeAware(p.edgeAware());
        WizardWandParameters.setEdgeStrength(p.edgeStrength());
        WizardWandParameters.edgePyramidOffsetProperty().set(p.edgePyramidOffset());
        WizardWandParameters.edgeNormalizationModeProperty().set(p.edgeNormalizationMode());
        WizardWandParameters.simplifyToleranceProperty().set(p.simplifyTolerance());
        WizardWandParameters.aggressiveSimplifyToleranceProperty().set(p.aggressiveSimplifyTolerance());
        WizardWandParameters.dwellDelayProperty().set(p.dwellDelay());
        WizardWandParameters.dwellExpansionRateProperty().set(p.dwellExpansionRate());
        WizardWandParameters.dwellMaxBoostProperty().set(p.dwellMaxBoost());
        WizardWandParameters.fixedDownsampleProperty().set(p.fixedDownsample());
        WizardWandParameters.fixedDownsampleLevelProperty().set(p.fixedDownsampleLevel());
        WizardWandParameters.useEnhancedBrushHandlerProperty().set(p.useEnhancedBrushHandler());
    }

    private WizardWandUserPresets() {}
}
