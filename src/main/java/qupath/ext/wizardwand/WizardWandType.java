package qupath.ext.wizardwand;

/**
 * Color space modes for the Wizard Wand tool.
 */
public enum WizardWandType {

    /** Grayscale image */
    GRAY,

    /** RGB color (default) */
    RGB,

    /** CIELAB color space with Euclidean distance */
    LAB_DISTANCE,

    /** HSV color space */
    HSV
}
