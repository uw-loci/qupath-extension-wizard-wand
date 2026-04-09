package qupath.ext.wizardwand;

/**
 * How the edge-aware mode decides which gradient pixels become barriers.
 */
public enum EdgeNormalizationMode {

    /**
     * Threshold is computed relative to the maximum gradient in the current
     * sampling window. Barriers shift as the cursor moves or the content
     * changes, but any scene will always have SOME barriers.
     */
    RELATIVE,

    /**
     * Threshold is computed against a fixed 0-255 scale. Barriers appear
     * only where the gradient magnitude exceeds an absolute value, so the
     * slider value means the same thing regardless of window content.
     */
    ABSOLUTE
}
