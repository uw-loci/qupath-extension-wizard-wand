package qupath.ext.wizardwand;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.Node;
import javafx.scene.input.MouseEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.gui.viewer.tools.PathTool;

/**
 * Custom PathTool implementation that registers the wand mouse event handler.
 * <p>
 * The standard {@code PathTools.createTool()} creates a tool that is identical
 * in behavior; we keep this thin wrapper so that the tool lifecycle (register /
 * deregister) can reset the wand's drawing state and dwell timer cleanly.
 */
public class WizardWandPathTool implements PathTool {

    private static final Logger logger = LoggerFactory.getLogger(WizardWandPathTool.class);

    private final WizardWandEventHandler mouseHandler;
    private final StringProperty name;
    private final ObjectProperty<Node> icon;
    private QuPathViewer viewer;

    public WizardWandPathTool(WizardWandEventHandler mouseHandler,
                              String name,
                              Node icon) {
        this.mouseHandler = mouseHandler;
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
        }
    }

    @Override
    public void deregisterTool(QuPathViewer viewer) {
        if (this.viewer == viewer) {
            logger.trace("Deregistering Wizard Wand tool from viewer {}", viewer);
            this.viewer = null;
            Node canvas = viewer.getView();
            canvas.removeEventHandler(MouseEvent.ANY, mouseHandler);
            // Reset all drawing state (dwell timer, flags, stale events)
            mouseHandler.resetDrawingState();
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
