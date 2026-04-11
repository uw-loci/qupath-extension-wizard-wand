package qupath.ext.wizardwand;

import javafx.animation.AnimationTimer;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.roi.GeometryTools;

/**
 * AnimationTimer that implements two-phase dwell behavior for the Wizard Wand:
 * <p>
 * <b>Phase 1 (sensitivity expansion):</b> After the dwell delay, sensitivity
 * boost grows logarithmically until it hits {@code dwellMaxBoost}. The selection
 * expands outward from the click point.
 * <p>
 * <b>Phase 2 (pyramid refinement):</b> Once sensitivity saturates, the timer
 * steps through finer pyramid levels (one level per 500ms, max 2 steps).
 * Each step re-evaluates the wand at a finer downsample and <em>splices</em>
 * the high-resolution boundary into the existing annotation: the area inside
 * the fine sampling window gets the accurate boundary; the area outside keeps
 * the coarse boundary. This lets users work zoomed out but still get precise
 * tissue boundaries by holding the mouse still.
 * <p>
 * This timer runs on the FX application thread (AnimationTimer guarantee),
 * so no cross-thread synchronization is needed.
 */
public class DwellExpansionTimer extends AnimationTimer {

    private static final Logger logger = LoggerFactory.getLogger(DwellExpansionTimer.class);

    /** Movement threshold in image pixels -- anything smaller counts as "still" */
    private static final double MOVEMENT_THRESHOLD_PX = 3.0;

    /** Wand sampling window size (must match WizardWandEventHandler.W) */
    private static final int W = 149;

    /** Maximum pyramid levels to step finer than the viewer's current level.
     *  The server's own pyramid structure ensures each level is efficiently
     *  loadable, so 2 steps is safe at any zoom level. */
    private static final int MAX_REFINEMENT_STEPS = 2;

    /** Milliseconds between each pyramid refinement step */
    private static final double REFINEMENT_INTERVAL_MS = 500.0;

    private final WizardWandEventHandler eventHandler;

    private boolean active = false;
    private long startTimeNanos = 0;
    private long lastMoveTimeNanos = 0;
    private double startX, startY;
    private int lastAppliedSteps = 0; // Track which step we last applied to avoid re-splicing

    public DwellExpansionTimer(WizardWandEventHandler eventHandler) {
        this.eventHandler = eventHandler;
    }

    /**
     * Start tracking dwell from a mouse press at the given image coordinates.
     */
    public void startDwell(double x, double y) {
        this.startX = x;
        this.startY = y;
        this.startTimeNanos = System.nanoTime();
        this.lastMoveTimeNanos = startTimeNanos;
        this.lastAppliedSteps = 0;
        this.active = true;
        WizardWandParameters.setDwellSensitivityBoost(0.0);
        WizardWandParameters.clearDwellDownsampleOverride();
        start(); // Start the AnimationTimer
    }

    /**
     * Called on mouse drag to check if the cursor has actually moved.
     * If movement exceeds threshold, resets the dwell clock.
     */
    public void onMouseMove(double x, double y) {
        double dx = x - startX;
        double dy = y - startY;
        if (dx * dx + dy * dy > MOVEMENT_THRESHOLD_PX * MOVEMENT_THRESHOLD_PX) {
            // Significant movement -- reset dwell
            lastMoveTimeNanos = System.nanoTime();
            startX = x;
            startY = y;
            lastAppliedSteps = 0;
            WizardWandParameters.setDwellSensitivityBoost(0.0);
            WizardWandParameters.clearDwellDownsampleOverride();
        }
    }

    /**
     * Stop dwell expansion and reset transient state.
     */
    public void stopDwell() {
        active = false;
        lastAppliedSteps = 0;
        stop(); // Stop the AnimationTimer
        WizardWandParameters.setDwellSensitivityBoost(0.0);
        WizardWandParameters.clearDwellDownsampleOverride();
    }

    @Override
    public void handle(long now) {
        if (!active) {
            return;
        }

        double dwellDelayMs = WizardWandParameters.getDwellDelay();
        double elapsedSinceLastMoveMs = (now - lastMoveTimeNanos) / 1_000_000.0;

        if (elapsedSinceLastMoveMs < dwellDelayMs) {
            // Haven't been still long enough yet
            return;
        }

        // Time spent in active dwell (after the delay period)
        double dwellTimeMs = elapsedSinceLastMoveMs - dwellDelayMs;

        // --- Phase 1: Sensitivity expansion (unchanged) ---
        double rate = WizardWandParameters.getDwellExpansionRate();
        double maxBoost = WizardWandParameters.getDwellMaxBoost();
        double dwellFactor = rate * Math.log(1.0 + dwellTimeMs / 200.0);
        dwellFactor = Math.min(dwellFactor, maxBoost);
        WizardWandParameters.setDwellSensitivityBoost(dwellFactor);

        logger.trace("Dwell expansion: boost={}, elapsed={}ms", dwellFactor, dwellTimeMs);

        // --- Phase 2: Pyramid refinement (starts when sensitivity saturates) ---
        if (dwellFactor >= maxBoost && rate > 0) {
            // Time since sensitivity saturated
            double satTimeMs = 200.0 * (Math.exp(maxBoost / rate) - 1.0);
            double refinementMs = dwellTimeMs - satTimeMs;
            int stepsDown = Math.max(0,
                    (int) Math.min(MAX_REFINEMENT_STEPS, refinementMs / REFINEMENT_INTERVAL_MS));

            if (stepsDown > 0 && stepsDown > lastAppliedSteps) {
                applyRefinement(stepsDown);
                // Don't call refreshCurrentShape here -- applyRefinement
                // directly updates the annotation ROI
                return;
            }
        }

        // Phase 1 (or Phase 2 with no new step): refresh via normal path
        eventHandler.refreshCurrentShape();
    }

    /**
     * Phase 2: compute a fine-resolution wand shape and splice it into the
     * existing coarse annotation. Only the area inside the fine sampling
     * window gets the finer boundary; the rest keeps the coarse boundary.
     */
    private void applyRefinement(int stepsDown) {
        QuPathViewer viewer = eventHandler.viewer();
        if (viewer == null || viewer.getServer() == null)
            return;

        double viewDs = Math.max(1, Math.round(viewer.getDownsampleFactor() * 4)) / 4.0;
        double[] levels = viewer.getServer().getPreferredDownsamples();
        if (levels == null || levels.length == 0)
            return;

        int currentIdx = findClosestLevelIndex(levels, viewDs);
        int targetIdx = Math.max(0, currentIdx - stepsDown);
        double fineDs = levels[targetIdx];

        // Already at the finest available level?
        if (fineDs >= viewDs) {
            return;
        }

        // Set the override so computeShapeAt uses the finer downsample
        WizardWandParameters.setDwellDownsampleOverride(fineDs);

        // Compute the fine-resolution wand shape
        Geometry fineGeom = eventHandler.computeShapeAt(
                viewer, startX, startY, false, false, false, false);

        if (fineGeom == null || fineGeom.isEmpty()) {
            WizardWandParameters.clearDwellDownsampleOverride();
            return;
        }

        // Get the existing annotation
        var selected = viewer.getSelectedObject();
        if (selected == null || !selected.isAnnotation() || !selected.isEditable()
                || !selected.hasROI() || !selected.getROI().isArea()) {
            WizardWandParameters.clearDwellDownsampleOverride();
            return;
        }

        Geometry existing = selected.getROI().getGeometry();

        // Compute the fine window's physical footprint as a rectangle
        double halfExtent = (W / 2.0) * fineDs;
        Geometry fineRect = createRectangle(startX, startY, halfExtent);

        // Splice: keep coarse edges OUTSIDE the fine window,
        // use fine edges INSIDE the fine window
        try {
            Geometry outsideFine = existing.difference(fineRect);
            Geometry result = outsideFine.union(fineGeom);

            if (!result.isEmpty()) {
                var newRoi = GeometryTools.geometryToROI(result, selected.getROI().getImagePlane());
                ((PathAnnotationObject) selected).setROI(newRoi);
                lastAppliedSteps = stepsDown;
                logger.debug("Pyramid refinement step {}: ds {} -> {} ({}x{}px window)",
                        stepsDown, String.format("%.1f", viewDs),
                        String.format("%.1f", fineDs),
                        (int) (W * fineDs), (int) (W * fineDs));
            }
        } catch (Exception ex) {
            logger.debug("Pyramid refinement splice failed: {}", ex.getMessage());
        }
    }

    /**
     * Create a rectangle geometry centered at (cx, cy) with the given half-extent.
     */
    private static Geometry createRectangle(double cx, double cy, double halfExtent) {
        var factory = new GeometryFactory();
        double x0 = cx - halfExtent;
        double y0 = cy - halfExtent;
        double x1 = cx + halfExtent;
        double y1 = cy + halfExtent;
        return factory.createPolygon(new Coordinate[]{
                new Coordinate(x0, y0), new Coordinate(x1, y0),
                new Coordinate(x1, y1), new Coordinate(x0, y1),
                new Coordinate(x0, y0)
        });
    }

    /**
     * Find the index in the preferred downsamples array whose value is closest
     * to the target downsample.
     */
    private static int findClosestLevelIndex(double[] levels, double target) {
        int bestIdx = 0;
        double bestDiff = Math.abs(levels[0] - target);
        for (int i = 1; i < levels.length; i++) {
            double diff = Math.abs(levels[i] - target);
            if (diff < bestDiff) {
                bestDiff = diff;
                bestIdx = i;
            }
        }
        return bestIdx;
    }
}
