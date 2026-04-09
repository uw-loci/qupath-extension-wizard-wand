package qupath.ext.wizardwand;

import javafx.event.EventHandler;
import javafx.scene.input.ScrollEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scroll event filter that adjusts wand sensitivity while actively drawing.
 * <p>
 * Registered as an event FILTER (not handler) so it fires before the viewer's
 * zoom handler. Only consumes scroll events when the wand is actively drawing
 * (mouse button held down). When not drawing, events pass through to normal zoom.
 */
public class WizardWandScrollHandler implements EventHandler<ScrollEvent> {

    private static final Logger logger = LoggerFactory.getLogger(WizardWandScrollHandler.class);

    private final WizardWandEventHandler eventHandler;

    public WizardWandScrollHandler(WizardWandEventHandler eventHandler) {
        this.eventHandler = eventHandler;
    }

    @Override
    public void handle(ScrollEvent e) {
        if (!eventHandler.isDrawing()) {
            // Not drawing - let normal zoom proceed
            return;
        }

        // Consume to prevent zoom while drawing
        e.consume();

        // Scroll up (positive deltaY) = increase sensitivity value = tighter selection (RGB/GRAY/HSV)
        // Scroll down (negative deltaY) = decrease sensitivity value = looser selection (RGB/GRAY/HSV)
        // Note: for LAB_DISTANCE mode the relationship is reversed
        double direction = e.getDeltaY() > 0 ? 1.0 : -1.0;
        WizardWandParameters.adjustSensitivity(direction);

        logger.trace("Wizard Wand sensitivity adjusted to {}",
                WizardWandParameters.getSensitivity());

        // Re-create the shape at current position with new sensitivity
        eventHandler.refreshCurrentShape();
    }
}
