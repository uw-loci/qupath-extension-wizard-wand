package qupath.ext.wizardwand;

/**
 * Built-in sensitivity presets that bundle sensitivity and closing size together.
 */
public enum SensitivityPreset {

    /** Tight selection, more smoothing */
    FINE(1.0, 3),

    /** Balanced defaults */
    STANDARD(2.0, 5),

    /** Larger regions, moderate smoothing */
    BROAD(4.0, 7),

    /** Very loose selection, heavy smoothing */
    AGGRESSIVE(8.0, 9);

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
