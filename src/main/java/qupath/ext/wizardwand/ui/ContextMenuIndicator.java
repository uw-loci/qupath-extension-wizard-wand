package qupath.ext.wizardwand.ui;

import java.util.List;
import java.util.function.BiConsumer;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.SkinBase;
import javafx.scene.shape.ClosePath;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import org.controlsfx.control.decoration.Decoration;
import org.controlsfx.control.decoration.Decorator;
import org.controlsfx.control.decoration.GraphicDecoration;

/**
 * The small triangle at the bottom-right corner of a toolbar button that has a right-click
 * menu, drawn like the one on QuPath's own Line tool button ({@code ToolBarComponent}).
 *
 * <p>The triangle is added only while the button is in a scene, visible and skinned. ControlsFX
 * adds it to the skin's children; with no skin yet it falls back to reflection on
 * {@code Parent.getChildren()}, which fails where JavaFX runs as named modules, as in the
 * packaged QuPath on Windows ({@code InaccessibleObjectException}: javafx.graphics does not
 * open javafx.scene).</p>
 *
 * <p>The same file is copied into Channel Names Viewer, Class Visibility, Polyline Wand and
 * Wizard Wand; {@code tools/check_appose_copies.py} keeps the copies identical.</p>
 */
public final class ContextMenuIndicator {

    private static final double WIDTH = 6;

    private ContextMenuIndicator() {
    }

    /**
     * Add the triangle to a toolbar button and keep it there.
     *
     * @param button   the toolbar button
     * @param showMenu shows the button's menu at a screen position (x, y) when the triangle is
     *                 clicked
     */
    public static void install(ButtonBase button, BiConsumer<Double, Double> showMenu) {
        Path triangle = new Path(
                new MoveTo(0, 0),
                new LineTo(WIDTH, 0),
                new LineTo(WIDTH / 2.0, Math.sqrt(WIDTH * WIDTH / 2.0)),
                new ClosePath());
        triangle.setTranslateX(-WIDTH);
        triangle.setTranslateY(-WIDTH);
        triangle.setRotate(-90);
        triangle.setStroke(null);
        triangle.setOpacity(0.5);
        triangle.fillProperty().bind(button.textFillProperty());
        triangle.setOnMouseClicked(e -> {
            showMenu.accept(e.getScreenX(), e.getScreenY());
            e.consume();
        });
        GraphicDecoration decoration = new GraphicDecoration(triangle, Pos.BOTTOM_RIGHT);

        // The skin drops the triangle when it rebuilds its children (a new graphic), and
        // ControlsFX drops every decoration of a node whose visibility changes.
        Runnable refresh = () -> Platform.runLater(() -> refresh(button, decoration));
        button.sceneProperty().addListener((obs, o, n) -> refresh.run());
        button.skinProperty().addListener((obs, o, n) -> refresh.run());
        button.graphicProperty().addListener((obs, o, n) -> refresh.run());
        button.visibleProperty().addListener((obs, o, n) -> refresh.run());
        refresh.run();
    }

    private static void refresh(ButtonBase button, Decoration decoration) {
        // Decorator calls with no scene are queued until there is one, and with no skin they
        // reach the children by reflection; wait for both.
        if (button.getScene() == null || !(button.getSkin() instanceof SkinBase) || !button.isVisible()) {
            return;
        }
        List<Decoration> current = Decorator.getDecorations(button);
        if (current != null && current.contains(decoration)) {
            Decorator.removeDecoration(button, decoration);
        }
        Decorator.addDecoration(button, decoration);
    }
}
