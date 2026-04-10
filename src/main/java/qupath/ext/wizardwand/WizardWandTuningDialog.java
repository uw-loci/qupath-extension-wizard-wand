package qupath.ext.wizardwand;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;

/**
 * Dialog that drives the "Tune wand from selection" workflow. Shows a progress
 * bar during the search and lets the user accept or cancel the result.
 * <p>
 * The search runs on a background thread that calls back to the FX thread
 * for parameter mutation and wand evaluation (because the OpenCV buffers
 * are FX-thread-bound). The dialog manages the lifecycle:
 * cancel flag, progress updates, and final apply-or-restore.
 */
public final class WizardWandTuningDialog {

    private static final Logger logger = LoggerFactory.getLogger(WizardWandTuningDialog.class);

    /**
     * Validate the current selection and run the tuning dialog.
     * Called from the toolbar context menu on the FX thread.
     */
    public static void show(QuPathGUI qupath) {
        var viewer = qupath == null ? null : qupath.getViewer();
        if (viewer == null) {
            Dialogs.showWarningNotification("Wizard Wand", "No viewer available.");
            return;
        }

        var selected = viewer.getSelectedObject();
        if (selected == null || !selected.hasROI() || !selected.getROI().isArea()) {
            Dialogs.showErrorMessage("Wizard Wand",
                    "Select an annotation with an area ROI to use as ground truth.");
            return;
        }

        var handler = WizardWandExtension.getSharedEventHandler();
        if (handler == null) {
            Dialogs.showErrorMessage("Wizard Wand", "Wizard Wand tool is not initialized.");
            return;
        }
        if (handler.isBusyDrawing()) {
            Dialogs.showWarningNotification("Wizard Wand",
                    "Cannot tune while actively drawing. Release the mouse button first.");
            return;
        }

        var gt = selected.getROI().getGeometry();
        var interior = gt.getInteriorPoint();
        double seedX = interior.getX();
        double seedY = interior.getY();

        // Build the dialog
        var dialog = new Dialog<ButtonType>();
        dialog.setTitle("Wizard Wand");
        dialog.setHeaderText("Tune wand from selection");

        var statusLabel = new Label("Preparing...");
        statusLabel.setWrapText(true);
        var progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(400);
        var bestLabel = new Label("");

        var content = new VBox(10, statusLabel, progressBar, bestLabel);
        content.setPadding(new Insets(10));
        content.setAlignment(Pos.CENTER_LEFT);
        dialog.getDialogPane().setContent(content);

        var okType = new ButtonType("Apply", ButtonBar.ButtonData.OK_DONE);
        var cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(okType, cancelType);

        // OK starts disabled until search completes
        Button okButton = (Button) dialog.getDialogPane().lookupButton(okType);
        okButton.setDisable(true);

        // Shared state
        final boolean[] cancelled = {false};
        final WizardWandTuner.Result[] resultHolder = {null};

        // Cancel handler
        dialog.setOnCloseRequest(event -> {
            if (resultHolder[0] == null) {
                // Search still running — set cancel flag
                cancelled[0] = true;
            }
        });

        // Progress callback (called from background thread, posts to FX)
        WizardWandTuner.Progress progress = new WizardWandTuner.Progress() {
            @Override
            public void update(int done, int total, double bestIoUSoFar) {
                Platform.runLater(() -> {
                    progressBar.setProgress((double) done / total);
                    statusLabel.setText(String.format("Searching... %d / %d", done, total));
                    bestLabel.setText(String.format("Best IoU so far: %.3f", bestIoUSoFar));
                });
            }

            @Override
            public boolean isCancelled() {
                return cancelled[0];
            }
        };

        // Run the search on a background thread. The tuner calls computeShapeAt
        // which MUST run on the FX thread (instance OpenCV buffers). So each
        // candidate evaluation is posted to the FX thread via Platform.runLater.
        // To keep things simple for v1, we run the entire search on the FX thread
        // via a daemon thread that feeds work to Platform.runLater. This blocks
        // the UI during each individual candidate evaluation (~10-20ms) but the
        // progress bar updates between candidates.
        //
        // Actually simpler: run the whole thing on a background thread that calls
        // Platform.runLater for each candidate. But computeShapeAt touches
        // instance buffers that are FX-thread-only. So we use invokeLater.
        var thread = new Thread(() -> {
            // The tuner mutates static params and calls computeShapeAt which
            // uses FX-thread buffers. We must run on the FX thread.
            // Use a blocking invokeLater pattern: post the entire search.
            Platform.runLater(() -> {
                try {
                    var result = WizardWandTuner.tune(handler, viewer, gt, seedX, seedY, progress);
                    resultHolder[0] = result;

                    if (cancelled[0]) {
                        return;
                    }

                    if (result.best() == null || result.iou() < 0.05) {
                        statusLabel.setText("No good match found.");
                        bestLabel.setText(String.format("Best IoU: %.3f (%d candidates evaluated)",
                                result.iou(), result.evaluated()));
                    } else {
                        var c = result.best();
                        statusLabel.setText(String.format(
                                "Done! Best IoU: %.3f (%d candidates evaluated)",
                                result.iou(), result.evaluated()));
                        bestLabel.setText(String.format(
                                "%s | sensitivity %.3f | sigma %.1f | smoothing %d%s%s",
                                c.wandType(), c.sensitivity(), c.sigma(), c.morphKernelSize(),
                                c.edgeAware() ? " | edge-aware" : "",
                                c.strictConnectivity() ? " | strict" : ""));
                        okButton.setDisable(false);
                    }
                } catch (Exception ex) {
                    logger.error("Wand tuning failed", ex);
                    statusLabel.setText("Error: " + ex.getMessage());
                }
            });
        }, "wizard-wand-tuner");
        thread.setDaemon(true);
        thread.start();

        // Show dialog (blocks)
        var choice = dialog.showAndWait();

        if (choice.isPresent() && choice.get() == okType && resultHolder[0] != null) {
            var best = resultHolder[0].best();
            if (best != null) {
                WizardWandParameters.setWandType(best.wandType());
                WizardWandParameters.setSensitivity(best.sensitivity());
                WizardWandParameters.setSigma(best.sigma());
                WizardWandParameters.setMorphKernelSize(best.morphKernelSize());
                WizardWandParameters.setEdgeAware(best.edgeAware());
                WizardWandParameters.setEdgeStrength(best.edgeStrength());
                WizardWandParameters.setStrictConnectivity(best.strictConnectivity());
                WizardWandParameters.setFillHoles(best.fillHoles());

                Dialogs.showInfoNotification("Wizard Wand",
                        String.format("Applied: %s, sensitivity %.3f, sigma %.1f, smoothing %d (IoU %.3f)",
                                best.wandType(), best.sensitivity(), best.sigma(),
                                best.morphKernelSize(), resultHolder[0].iou()));
            }
        } else {
            // Cancel or close: snapshot was already restored by the tuner's finally block
            cancelled[0] = true;
        }
    }

    private WizardWandTuningDialog() {}
}
