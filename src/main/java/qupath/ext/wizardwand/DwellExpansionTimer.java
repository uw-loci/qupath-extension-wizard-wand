package qupath.ext.wizardwand;

import javafx.animation.AnimationTimer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AnimationTimer that implements progressive "dwell expansion" for the Wizard Wand.
 * <p>
 * When the user clicks and holds without moving for longer than the dwell delay,
 * this timer progressively increases the sensitivity boost, causing the wand
 * selection to expand outward from the click point.
 * <p>
 * The expansion rate is logarithmic (decelerating) -- fast initial growth that
 * gradually slows, preventing the selection from running away while giving
 * satisfying immediate feedback.
 * <p>
 * This timer runs on the FX application thread (AnimationTimer guarantee),
 * so no cross-thread synchronization is needed.
 */
public class DwellExpansionTimer extends AnimationTimer {

    private static final Logger logger = LoggerFactory.getLogger(DwellExpansionTimer.class);

    /** Movement threshold in image pixels -- anything smaller counts as "still" */
    private static final double MOVEMENT_THRESHOLD_PX = 3.0;

    private final WizardWandEventHandler eventHandler;

    private boolean active = false;
    private long startTimeNanos = 0;
    private long lastMoveTimeNanos = 0;
    private double startX, startY;

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
        this.active = true;
        WizardWandParameters.setDwellSensitivityBoost(0.0);
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
            WizardWandParameters.setDwellSensitivityBoost(0.0);
        }
    }

    /**
     * Stop dwell expansion and reset transient state.
     */
    public void stopDwell() {
        active = false;
        stop(); // Stop the AnimationTimer
        WizardWandParameters.setDwellSensitivityBoost(0.0);
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

        // Logarithmic expansion: fast initial growth, decelerating over time
        // dwellFactor = rate * log(1 + dwellTime / 200)
        double rate = WizardWandParameters.getDwellExpansionRate();
        double dwellFactor = rate * Math.log(1.0 + dwellTimeMs / 200.0);

        // Cap the dwell boost to prevent runaway expansion
        double maxBoost = 10.0;
        dwellFactor = Math.min(dwellFactor, maxBoost);

        WizardWandParameters.setDwellSensitivityBoost(dwellFactor);

        logger.trace("Dwell expansion: boost={}, elapsed={}ms", dwellFactor, dwellTimeMs);

        // Re-create shape with the boosted sensitivity
        eventHandler.refreshCurrentShape();
    }
}
