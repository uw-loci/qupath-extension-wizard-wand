package qupath.ext.wizardwand;

/**
 * Built-in sensitivity presets that bundle sensitivity and closing size together.
 * Higher sensitivity = bigger selection (consistent across all color modes).
 */
public enum SensitivityPreset {

    /** Tight, precise selection with light smoothing */
    FINE(0.3, 3),

    /** Balanced defaults -- slightly more generous than original wand */
    STANDARD(1.0, 5),

    /** Larger regions, moderate smoothing */
    BROAD(2.0, 7),

    /** Very loose selection, heavy smoothing */
    AGGRESSIVE(4.0, 9);

    private final double sensitivity;
    private final int closingSize;

    SensitivityPreset(double sensitivity, int closingSize) {
        this.sensitivity = sensitivity;
        this.closingSize = closingSize;
    }

    public double getSensitivity() {
        return sensitivity;
    }

    public int getClosingSize() {
        return closingSize;
    }
}
