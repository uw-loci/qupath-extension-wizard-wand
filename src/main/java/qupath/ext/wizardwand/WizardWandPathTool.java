package qupath.ext.wizardwand;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.Node;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.gui.viewer.tools.PathTool;

/**
 * Custom PathTool implementation that registers both mouse and scroll event handlers.
 * <p>
 * The standard {@code PathTools.createTool()} only supports MouseEvent handlers.
 * This custom implementation adds a ScrollEvent filter for mid-draw sensitivity
 * adjustment via the mouse wheel.
 */
public class WizardWandPathTool implements PathTool {

    private static final Logger logger = LoggerFactory.getLogger(WizardWandPathTool.class);

    private final WizardWandEventHandler mouseHandler;
    private final WizardWandScrollHandler scrollHandler;
    private final StringProperty name;
    private final ObjectProperty<Node> icon;
    private QuPathViewer viewer;

    public WizardWandPathTool(WizardWandEventHandler mouseHandler,
                              WizardWandScrollHandler scrollHandler,
                              String name,
                              Node icon) {
        this.mouseHandler = mouseHandler;
        this.scrollHandler = scrollHandler;
        this.name = new SimpleStringProperty(name);
        this.icon = new SimpleObjectProperty<>(icon);
    }

    @Override
    public void registerTool(QuPathViewer viewer) {
        // Deregister from any previous viewer
        if (this.viewer != null) {
            deregisterTool(this.viewer);
        }

        this.viewer = viewer;
        if (viewer != null) {
            logger.trace("Registering Wizard Wand tool on viewer {}", viewer);
            Node canvas = viewer.getView();
            canvas.addEventHandler(MouseEvent.ANY, mouseHandler);
            // Use event FILTER for scroll so we fire before the zoom handler
            canvas.addEventFilter(ScrollEvent.SCROLL, scrollHandler);
        }
    }

    @Override
    public void deregisterTool(QuPathViewer viewer) {
        if (this.viewer == viewer) {
            logger.trace("Deregistering Wizard Wand tool from viewer {}", viewer);
            this.viewer = null;
            Node canvas = viewer.getView();
            canvas.removeEventHandler(MouseEvent.ANY, mouseHandler);
            canvas.removeEventFilter(ScrollEvent.SCROLL, scrollHandler);
            // Stop any active dwell timer
            mouseHandler.stopDwell();
        }
    }

    @Override
    public ReadOnlyStringProperty nameProperty() {
        return name;
    }

    @Override
    public ReadOnlyObjectProperty<Node> iconProperty() {
        return icon;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + ": " + name.get();
    }
}
