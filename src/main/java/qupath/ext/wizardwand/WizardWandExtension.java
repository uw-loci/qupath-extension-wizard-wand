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
import qupath.fx.dialogs.Dialogs;
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
    private static WizardWandEventHandler sharedEventHandler;

    /** Get the shared event handler for headless wand evaluation (tuner). */
    public static WizardWandEventHandler getSharedEventHandler() {
        return sharedEventHandler;
    }

    @Override
    public String getName() {
        return "Wizard Wand";
    }

    @Override
    public String getDescription() {
        return "Enhanced wand tool with dwell expansion, live smoothing, "
                + "edge-aware selection, and more.";
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
            sharedEventHandler = eventHandler;

            // Use FontAwesome MAGIC glyph (wand with sparkles) to distinguish
            // from the built-in wand tool which uses icoMoon wand glyph
            var icon = createWizardWandIcon(QuPathGUI.TOOLBAR_ICON_SIZE);

            PathTool wizardWandTool = new WizardWandPathTool(
                    eventHandler, "Wizard Wand", icon);

            logger.debug("Installing Wizard Wand tool");
            Platform.runLater(() -> {
                var keyCombination = new KeyCodeCombination(KeyCode.W, KeyCombination.SHIFT_DOWN);
                qupath.getToolManager().installTool(wizardWandTool, keyCombination);
                qupath.getToolManager().getToolAction(wizardWandTool).setLongText(String.format(
                        """
                        (%s) Click and drag to draw with the Wizard Wand tool.
                        Hold still to expand selection (dwell).
                        Hold Shift while dragging for smooth annotations.
                        Right-click toolbar button for presets and options.
                        """,
                        keyCombination.getDisplayText()
                ));
                // Attach context menu to the toolbar button when it becomes available
                attachContextMenuToToolbarButton(qupath);
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
     * Uses delayed Platform.runLater to wait for the toolbar to process the
     * newly-installed tool, then iterates toolbar items to find the button
     * whose tooltip matches "Wizard Wand".
     */
    private void attachContextMenuToToolbarButton(QuPathGUI qupath) {
        // Double runLater: first lets the toolbar process the new tool,
        // second runs after button creation
        Platform.runLater(() -> Platform.runLater(() -> tryAttachContextMenu(qupath, 0)));
    }

    private void tryAttachContextMenu(QuPathGUI qupath, int attempt) {
        var toolBar = qupath.getToolBar();
        if (toolBar == null) {
            logger.warn("Wizard Wand: could not get toolbar for context menu");
            return;
        }

        var button = findWizardWandButton(toolBar);
        if (button != null) {
            button.setContextMenu(buildContextMenu());
            logger.info("Wizard Wand context menu attached to toolbar button");
            return;
        }

        // Not found yet - retry a few times in case toolbar is still being built
        if (attempt < 10) {
            Platform.runLater(() -> tryAttachContextMenu(qupath, attempt + 1));
        } else {
            logger.warn("Wizard Wand: could not find toolbar button after {} attempts", attempt);
        }
    }

    /**
     * Find the Wizard Wand button in the toolbar by checking tooltip text
     * and action properties.
     */
    private javafx.scene.control.ButtonBase findWizardWandButton(javafx.scene.control.ToolBar toolBar) {
        for (var item : toolBar.getItems()) {
            var button = findButton(item);
            if (button == null)
                continue;
            // Check tooltip
            var tooltip = button.getTooltip();
            if (tooltip != null && tooltip.getText() != null
                    && tooltip.getText().contains("Wizard Wand")) {
                return button;
            }
            // Check text
            if ("Wizard Wand".equals(button.getText())) {
                return button;
            }
            // Check ControlsFX action stored in properties
            var action = button.getProperties().get("controlsfx.actions.action");
            if (action instanceof org.controlsfx.control.action.Action a
                    && "Wizard Wand".equals(a.getText())) {
                return button;
            }
        }
        return null;
    }

    private javafx.scene.control.ButtonBase findButton(javafx.scene.Node node) {
        if (node instanceof javafx.scene.control.ButtonBase b)
            return b;
        if (node instanceof javafx.scene.Parent p) {
            for (var child : p.getChildrenUnmodifiable()) {
                var found = findButton(child);
                if (found != null)
                    return found;
            }
        }
        return null;
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

        // Tune wand from selection
        var tuneItem = new javafx.scene.control.MenuItem("Tune wand from selection...");
        tuneItem.setOnAction(e -> WizardWandTuningDialog.show(QuPathGUI.getInstance()));
        menu.getItems().add(tuneItem);

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
            confirm.getDialogPane().setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
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
