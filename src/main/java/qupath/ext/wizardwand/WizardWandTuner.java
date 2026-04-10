package qupath.ext.wizardwand;

import org.locationtech.jts.geom.Geometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.viewer.QuPathViewer;

/**
 * Search engine for the "Tune wand from selection" feature. Sweeps the wand's
 * parameter space, evaluating each candidate by running
 * {@link WizardWandEventHandler#computeShapeAt} at a seed inside the
 * ground-truth geometry and scoring the result by IoU.
 * <p>
 * This class has no JavaFX dependencies (no Platform.runLater, no UI). It is
 * called from a background task that the dialog manages.
 */
public final class WizardWandTuner {

    private static final Logger logger = LoggerFactory.getLogger(WizardWandTuner.class);

    /** A single candidate parameter set to evaluate. */
    public record Candidate(
            WizardWandType wandType, double sensitivity, double sigma,
            int morphKernelSize, boolean edgeAware, double edgeStrength,
            boolean strictConnectivity, boolean fillHoles
    ) {}

    /** The result of a tuning run. */
    public record Result(Candidate best, double iou, int evaluated, int total) {}

    /** Progress callback so the dialog can update its bar and cancel the search. */
    public interface Progress {
        void update(int done, int total, double bestIoUSoFar);
        boolean isCancelled();
    }

    // --- Coarse grid ---
    private static final WizardWandType[] WAND_TYPES = {
            WizardWandType.RGB, WizardWandType.GRAY, WizardWandType.LAB_DISTANCE, WizardWandType.HSV
    };
    private static final double[] SENSITIVITY_COARSE = {0.1, 0.2, 0.4, 0.7, 1.0, 1.5, 2.5, 4.0};
    private static final double[] SIGMA_COARSE = {1.0, 3.0, 6.0};
    private static final int[] MORPH_COARSE = {0, 5, 11};

    // --- Fine grid multipliers ---
    private static final double[] SENS_FINE_MULTS = {0.7, 0.85, 1.0, 1.18, 1.4};
    private static final double[] SIGMA_FINE_MULTS = {0.5, 1.0, 2.0};
    private static final int[] MORPH_FINE_DELTAS = {-2, 0, 2, 4};

    /**
     * Run the full two-pass tuning search on the FX application thread.
     * <p>
     * <b>Threading:</b> this method mutates {@link WizardWandParameters}
     * (via snapshot/restore) and calls {@code handler.computeShapeAt}
     * which reuses the handler's instance buffers. Both require the FX
     * thread. The method saves and restores the user's settings via
     * {@code try/finally} so no mutation is permanent even on cancellation.
     *
     * @param handler the shared event handler (must not be busy drawing)
     * @param viewer  the active viewer
     * @param groundTruth the user's ground-truth geometry (image coords)
     * @param seedX   image-coordinate X of the seed (inside the GT)
     * @param seedY   image-coordinate Y of the seed (inside the GT)
     * @param progress progress callback (may be null for silent mode)
     * @return the best result found, or a zero-IoU result if nothing matched
     */
    public static Result tune(WizardWandEventHandler handler,
                              QuPathViewer viewer,
                              Geometry groundTruth,
                              double seedX, double seedY,
                              Progress progress) {

        var snapshot = WizardWandParameters.captureSnapshot();
        WizardWandParameters.setDwellSensitivityBoost(0);

        // Count total candidates
        int coarseTotal = WAND_TYPES.length * SENSITIVITY_COARSE.length
                * SIGMA_COARSE.length * MORPH_COARSE.length;
        int fineTotal = SENS_FINE_MULTS.length * SIGMA_FINE_MULTS.length
                * MORPH_FINE_DELTAS.length * 2 /* edgeAware */ * 2 /* connectivity */;
        int total = coarseTotal + fineTotal;

        Candidate bestCandidate = null;
        double bestIoU = -1;
        int evaluated = 0;

        try {
            // --- Coarse pass ---
            for (var type : WAND_TYPES) {
                for (double sens : SENSITIVITY_COARSE) {
                    for (double sig : SIGMA_COARSE) {
                        for (int morph : MORPH_COARSE) {
                            if (progress != null && progress.isCancelled())
                                return new Result(bestCandidate, Math.max(bestIoU, 0), evaluated, total);

                            var c = new Candidate(type, sens, sig, morph, false, 0.5, false, true);
                            double iou = evaluateCandidate(handler, viewer, groundTruth, seedX, seedY, c);
                            evaluated++;

                            if (iou > bestIoU || (iou == bestIoU && sig < (bestCandidate == null ? Double.MAX_VALUE : bestCandidate.sigma()))) {
                                bestIoU = iou;
                                bestCandidate = c;
                            }

                            if (progress != null)
                                progress.update(evaluated, total, bestIoU);
                        }
                    }
                }
            }

            // --- Fine pass around the coarse winner ---
            if (bestCandidate != null) {
                for (double sensMult : SENS_FINE_MULTS) {
                    double fineSens = bestCandidate.sensitivity() * sensMult;
                    if (fineSens < 0.05 || fineSens > 5.0) { evaluated += SIGMA_FINE_MULTS.length * MORPH_FINE_DELTAS.length * 4; continue; }

                    for (double sigMult : SIGMA_FINE_MULTS) {
                        double fineSig = Math.max(0.5, bestCandidate.sigma() * sigMult);

                        for (int morphDelta : MORPH_FINE_DELTAS) {
                            int fineMorph = bestCandidate.morphKernelSize() + morphDelta;
                            if (fineMorph < 0) fineMorph = 0;
                            if (fineMorph > 0 && fineMorph % 2 == 0) fineMorph++;

                            for (boolean ea : new boolean[]{false, true}) {
                                for (boolean strict : new boolean[]{false, true}) {
                                    if (progress != null && progress.isCancelled())
                                        return new Result(bestCandidate, Math.max(bestIoU, 0), evaluated, total);

                                    var c = new Candidate(bestCandidate.wandType(), fineSens, fineSig,
                                            fineMorph, ea, 0.5, strict, true);
                                    double iou = evaluateCandidate(handler, viewer, groundTruth, seedX, seedY, c);
                                    evaluated++;

                                    if (iou > bestIoU) {
                                        bestIoU = iou;
                                        bestCandidate = c;
                                    }

                                    if (progress != null)
                                        progress.update(evaluated, total, bestIoU);
                                }
                            }
                        }
                    }
                }
            }

            logger.info("Wand tuner: best IoU={} candidate={} ({}/{} evaluated)",
                    String.format("%.3f", bestIoU), bestCandidate, evaluated, total);

            return new Result(bestCandidate, Math.max(bestIoU, 0), evaluated, total);

        } finally {
            WizardWandParameters.applySnapshot(snapshot);
        }
    }

    /**
     * Evaluate a single candidate: apply its params, run the wand, compute IoU.
     */
    private static double evaluateCandidate(WizardWandEventHandler handler,
                                             QuPathViewer viewer,
                                             Geometry groundTruth,
                                             double seedX, double seedY,
                                             Candidate c) {
        // Apply candidate to static params
        WizardWandParameters.setWandType(c.wandType());
        WizardWandParameters.setSensitivity(c.sensitivity());
        WizardWandParameters.setSigma(c.sigma());
        WizardWandParameters.setMorphKernelSize(c.morphKernelSize());
        WizardWandParameters.setEdgeAware(c.edgeAware());
        WizardWandParameters.setEdgeStrength(c.edgeStrength());
        WizardWandParameters.setStrictConnectivity(c.strictConnectivity());
        WizardWandParameters.setFillHoles(c.fillHoles());

        Geometry result = handler.computeShapeAt(viewer, seedX, seedY,
                false, false, false, false);

        if (result == null || result.isEmpty())
            return 0;

        try {
            double interArea = result.intersection(groundTruth).getArea();
            if (interArea == 0) return 0;
            double unionArea = result.getArea() + groundTruth.getArea() - interArea;
            return unionArea > 0 ? interArea / unionArea : 0;
        } catch (Exception e) {
            // TopologyException etc.
            return 0;
        }
    }

    private WizardWandTuner() {}
}
