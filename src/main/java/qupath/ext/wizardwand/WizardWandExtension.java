package qupath.ext.wizardwand;

import javafx.application.Platform;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.tools.IconFactory;
import qupath.lib.gui.tools.IconFactory.PathIcons;
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

            // Use the WAND_TOOL icon -- same glyph, users distinguish by toolbar position and name
            var icon = IconFactory.createNode(
                    QuPathGUI.TOOLBAR_ICON_SIZE,
                    QuPathGUI.TOOLBAR_ICON_SIZE,
                    PathIcons.WAND_TOOL);

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
