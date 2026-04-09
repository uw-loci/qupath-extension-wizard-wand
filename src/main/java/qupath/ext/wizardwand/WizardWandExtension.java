package qupath.ext.wizardwand;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.controlsfx.glyphfont.FontAwesome;
import org.controlsfx.glyphfont.GlyphFontRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.viewer.tools.PathTool;

/**
 * QuPath extension that provides the Wizard Wand tool -- an enhanced wand
 * with scroll-wheel sensitivity adjustment, dwell expansion, live smoothing,
 * edge-aware selection, hole filling, and more.
 */
public class WizardWandExtension implements QuPathExtension {

    private static final Logger logger = LoggerFactory.getLogger(WizardWandExtension.class);

    private boolean isInstalled = false;

    @Override
    public String getName() {
        return "Wizard Wand";
    }

    @Override
    public String getDescription() {
        return "Enhanced wand tool with scroll-wheel sensitivity, dwell expansion, "
                + "live smoothing, edge-aware selection, and more.";
    }

    @Override
    public void installExtension(QuPathGUI qupath) {
        if (isInstalled) {
            logger.debug("Wizard Wand extension already installed");
            return;
        }
        isInstalled = true;

        // Install preferences on the FX thread
        installPreferences(qupath);

        // Load OpenCV and install tool on a background thread
        // (same pattern as ProcessingExtension.installWand())
        var t = new Thread(() -> {
            if (!GeneralTools.isWindows()) {
                try {
                    org.bytedeco.openblas.global.openblas.blas_set_num_threads(1);
                } catch (Exception e) {
                    logger.debug("Could not set OpenBLAS threads: {}", e.getMessage());
                }
            }

            loadCoreClasses();

            // Create the tool components
            var eventHandler = new WizardWandEventHandler();
            var scrollHandler = new WizardWandScrollHandler(eventHandler);

            // Use FontAwesome MAGIC glyph (wand with sparkles) to distinguish
            // from the built-in wand tool which uses icoMoon wand glyph
            var icon = createWizardWandIcon(QuPathGUI.TOOLBAR_ICON_SIZE);

            PathTool wizardWandTool = new WizardWandPathTool(
                    eventHandler, scrollHandler, "Wizard Wand", icon);

            logger.debug("Installing Wizard Wand tool");
            Platform.runLater(() -> {
                var keyCombination = new KeyCodeCombination(KeyCode.W, KeyCombination.SHIFT_DOWN);
                qupath.getToolManager().installTool(wizardWandTool, keyCombination);
                qupath.getToolManager().getToolAction(wizardWandTool).setLongText(String.format(
                        """
                        (%s) Click and drag to draw with the Wizard Wand tool.
                        Scroll wheel while drawing to adjust sensitivity.
                        Hold still to expand selection (dwell).
                        Hold Shift while dragging for smooth annotations.
                        Right-click toolbar button for presets and options.
                        """,
                        keyCombination.getDisplayText()
                ));
                // Attach context menu to the toolbar button when it becomes available
                attachContextMenuToToolbarButton(icon);
            });
        }, "wizard-wand-init");
        t.setDaemon(true);
        t.start();
    }

    private void installPreferences(QuPathGUI qupath) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> installPreferences(qupath));
            return;
        }
        WizardWandPreferences.installPreferences(qupath);
    }

    /**
     * Create a distinctive icon for the Wizard Wand using FontAwesome's MAGIC glyph
     * (wand with sparkles), colored to match annotation objects.
     */
    private static Node createWizardWandIcon(int size) {
        var fontAwesome = GlyphFontRegistry.font("FontAwesome");
        var glyph = fontAwesome.create(FontAwesome.Glyph.MAGIC).size(size);
        glyph.setAlignment(javafx.geometry.Pos.CENTER);
        glyph.setContentDisplay(javafx.scene.control.ContentDisplay.CENTER);
        glyph.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        glyph.getStyleClass().add("qupath-icon");
        // Bind color to annotation color preference so it matches the theme
        glyph.textFillProperty().bind(javafx.beans.binding.Bindings.createObjectBinding(
                () -> qupath.lib.gui.tools.ColorToolsFX.getCachedColor(
                        qupath.lib.gui.prefs.PathPrefs.colorDefaultObjectsProperty().get()),
                qupath.lib.gui.prefs.PathPrefs.colorDefaultObjectsProperty()));
        return glyph;
    }

    /**
     * Attach a context menu to the Wizard Wand's toolbar button.
     * <p>
     * The icon Node is passed as the graphic of the ToggleButton created by
     * QuPath's toolbar. We watch the icon's parent property to detect when
     * it's added to the button, then walk up the parent chain to find the
     * ToggleButton and set its context menu.
     */
    private void attachContextMenuToToolbarButton(Node icon) {
        // If already in the scene, attach immediately
        if (icon.getParent() != null) {
            walkUpAndAttach(icon);
            return;
        }
        // Otherwise, wait for the icon to be added to the scene
        icon.parentProperty().addListener((obs, oldParent, newParent) -> {
            if (newParent != null) {
                walkUpAndAttach(icon);
            }
        });
    }

    private void walkUpAndAttach(Node icon) {
        javafx.scene.Parent p = icon.getParent();
        while (p != null && !(p instanceof javafx.scene.control.ButtonBase)) {
            p = p.getParent();
        }
        if (p instanceof javafx.scene.control.ButtonBase button) {
            button.setContextMenu(buildContextMenu());
            logger.debug("Wizard Wand context menu attached to toolbar button");
        }
    }

    /**
     * Build the context menu shown when right-clicking the Wizard Wand toolbar button.
     * Includes sensitivity presets and a reset option.
     */
    private javafx.scene.control.ContextMenu buildContextMenu() {
        var menu = new javafx.scene.control.ContextMenu();

        // Sensitivity presets submenu
        var presetsMenu = new javafx.scene.control.Menu("Sensitivity presets");
        for (var preset : SensitivityPreset.values()) {
            var item = new javafx.scene.control.MenuItem(
                    preset.name().charAt(0) + preset.name().substring(1).toLowerCase()
                            + String.format(" (sensitivity %.2f)", preset.getSensitivity()));
            item.setOnAction(e -> WizardWandParameters.applyPreset(preset));
            presetsMenu.getItems().add(item);
        }
        menu.getItems().add(presetsMenu);

        menu.getItems().add(new javafx.scene.control.SeparatorMenuItem());

        // Reset preferences
        var resetItem = new javafx.scene.control.MenuItem("Reset Wizard Wand preferences");
        resetItem.setOnAction(e -> {
            var confirm = new javafx.scene.control.Alert(
                    javafx.scene.control.Alert.AlertType.CONFIRMATION,
                    "Reset all Wizard Wand preferences to their defaults?\n\n"
                            + "This only affects Wizard Wand settings; other QuPath "
                            + "preferences will not be changed.",
                    javafx.scene.control.ButtonType.OK,
                    javafx.scene.control.ButtonType.CANCEL);
            confirm.setHeaderText("Reset Wizard Wand preferences");
            confirm.setTitle("Wizard Wand");
            confirm.showAndWait().ifPresent(result -> {
                if (result == javafx.scene.control.ButtonType.OK) {
                    WizardWandParameters.resetDefaults();
                    logger.info("Wizard Wand preferences reset to defaults");
                }
            });
        });
        menu.getItems().add(resetItem);

        return menu;
    }

    /**
     * Pre-load OpenCV classes to avoid threading issues on first use.
     */
    private void loadCoreClasses() {
        try {
            Loader.load(opencv_core.class);
            Loader.load(opencv_imgproc.class);
        } catch (Exception e) {
            logger.error("Error loading OpenCV classes for Wizard Wand: {}", e.getMessage(), e);
        }
    }
}
