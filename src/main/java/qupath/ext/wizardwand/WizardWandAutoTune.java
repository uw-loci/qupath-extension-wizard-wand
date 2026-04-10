package qupath.ext.wizardwand;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.bytedeco.opencv.global.opencv_core.*;

import javafx.concurrent.Task;
import org.bytedeco.javacpp.indexer.FloatIndexer;
import org.bytedeco.javacpp.indexer.IntIndexer;
import org.bytedeco.javacpp.indexer.UByteIndexer;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.MatVector;
import org.bytedeco.opencv.opencv_core.Point;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.bytedeco.opencv.opencv_core.Size;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.util.AffineTransformation;
import org.locationtech.jts.geom.util.GeometryCombiner;
import org.locationtech.jts.simplify.VWSimplifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.gui.viewer.overlays.HierarchyOverlay;
import qupath.lib.gui.viewer.overlays.PathOverlay;
import qupath.lib.objects.PathObject;
import qupath.lib.regions.ImageRegion;
import qupath.lib.roi.GeometryTools;

/**
 * Auto-tune feature for the Wizard Wand. Given a user-drawn ground-truth
 * annotation, searches the wand's parameter space (sensitivity, sigma,
 * morphKernelSize) to find settings that produce a similar selection, then
 * applies those settings.
 * <p>
 * The pipeline stages are replicated from {@link WizardWandEventHandler#createShape}
 * but simplified for search: no diagnostic logging, no dwell, no mouse events.
 * Runs on a background thread using its own OpenCV Mat objects to avoid
 * interfering with the interactive wand.
 */
public final class WizardWandAutoTune {

    private static final Logger logger = LoggerFactory.getLogger(WizardWandAutoTune.class);

    private static final int W = 149;

    /**
     * Holds the result of an auto-tune search.
     */
    public record Result(
            double sensitivity, double sigma, int morphKernelSize,
            double iou, int trialsRun
    ) {}

    // --- Search grid ---

    private static final double[] SENSITIVITY_COARSE = {0.15, 0.25, 0.35, 0.5, 0.7, 1.0, 1.5, 2.5};
    private static final double[] SIGMA_GRID = {1.0, 2.0, 4.0, 6.0};
    private static final int[] MORPH_GRID = {0, 3, 5, 9};

    /**
     * Build a JavaFX Task that runs the auto-tune search and returns the best
     * result. The task should be submitted to a thread pool; progress updates
     * are provided for use with a ProgressDialog.
     * <p>
     * The image patch is captured on the CALLING thread (must be FX thread)
     * before the task starts, so the background search thread never touches the
     * viewer's region store.
     *
     * @param viewer   the active viewer (used for image capture and server bounds)
     * @param gtObject the user's ground-truth annotation
     * @return a Task that yields the best Result, or throws if validation fails
     */
    public static Task<Result> buildTask(QuPathViewer viewer, PathObject gtObject) {
        // --- Pre-validation (on FX thread) ---
        var gtGeom = gtObject.getROI().getGeometry();
        var interior = gtGeom.getInteriorPoint();
        double seedX = interior.getX();
        double seedY = interior.getY();

        double downsample = Math.max(1, Math.round(viewer.getDownsampleFactor() * 4)) / 4.0;

        // Check that GT fits within the wand's sampling window
        var gtEnv = gtGeom.getEnvelopeInternal();
        double reach = W * downsample;
        if (gtEnv.getWidth() > reach || gtEnv.getHeight() > reach) {
            throw new IllegalArgumentException(String.format(
                    "The selected annotation (%.0f x %.0f px) is larger than the wand's "
                            + "sampling window (%.0f x %.0f px at the current zoom). "
                            + "Zoom in or draw a smaller reference annotation.",
                    gtEnv.getWidth(), gtEnv.getHeight(), reach, reach));
        }

        // --- Capture image patch on FX thread ---
        WizardWandType type = WizardWandParameters.getWandType();
        boolean doGray = type == WizardWandType.GRAY;
        BufferedImage img = doGray
                ? new BufferedImage(W, W, BufferedImage.TYPE_BYTE_GRAY)
                : new BufferedImage(W, W, BufferedImage.TYPE_3BYTE_BGR);
        captureRegion(viewer, img, seedX, seedY, downsample);
        byte[] pixels = ((DataBufferByte) img.getRaster().getDataBuffer()).getData().clone();
        int nChannels = doGray ? 1 : 3;

        int serverWidth = viewer.getServerWidth();
        int serverHeight = viewer.getServerHeight();

        // Read held-constant params from current preferences
        int connectivity = WizardWandParameters.getConnectivity();
        boolean edgeAware = WizardWandParameters.getEdgeAware();
        double edgeStrength = WizardWandParameters.getEdgeStrength();
        int edgePyramidOffset = WizardWandParameters.getEdgePyramidOffset();
        EdgeNormalizationMode edgeNormMode = WizardWandParameters.getEdgeNormalizationMode();
        boolean fillHoles = WizardWandParameters.getFillHoles();
        int minHoleSize = WizardWandParameters.getMinHoleSize();
        double simplifyTolerance = WizardWandParameters.getSimplifyTolerance();

        // --- Build the background task ---
        int totalTrials = SENSITIVITY_COARSE.length * SIGMA_GRID.length * MORPH_GRID.length;

        return new Task<>() {
            @Override
            protected Result call() {
                double bestIoU = -1;
                double bestSens = 0.5, bestSigma = 4.0;
                int bestMorph = 5;
                int trial = 0;

                // Allocate reusable OpenCV buffers for this thread
                try (var trialMat = new Mat(W, W, CV_8UC(nChannels));
                     var trialMask = new Mat(W + 2, W + 2, CV_8UC1);
                     var trialFloat = new Mat(W, W, CV_32FC3);
                     var meanMat = new Mat();
                     var stddevMat = new Mat()) {

                    for (double sens : SENSITIVITY_COARSE) {
                        for (double sig : SIGMA_GRID) {
                            for (int morph : MORPH_GRID) {
                                if (isCancelled()) return null;

                                Geometry result = runTrial(
                                        pixels, nChannels, type,
                                        seedX, seedY, downsample,
                                        sens, sig, morph, connectivity,
                                        edgeAware, edgeStrength,
                                        fillHoles, minHoleSize, simplifyTolerance,
                                        serverWidth, serverHeight,
                                        trialMat, trialMask, trialFloat, meanMat, stddevMat);

                                double iou = computeIoU(result, gtGeom);
                                if (iou > bestIoU || (iou == bestIoU && sig < bestSigma)) {
                                    bestIoU = iou;
                                    bestSens = sens;
                                    bestSigma = sig;
                                    bestMorph = morph;
                                }

                                trial++;
                                updateProgress(trial, totalTrials);
                            }
                        }
                    }

                    // Fine pass around the winner (if coarse IoU < 0.9)
                    int fineTrials = 0;
                    if (bestIoU < 0.9) {
                        double[] fineGrid = {
                                bestSens * 0.8, bestSens * 0.9,
                                bestSens * 1.1, bestSens * 1.2, bestSens * 1.15
                        };
                        for (double fineSens : fineGrid) {
                            if (fineSens < 0.05 || fineSens > 5.0) continue;
                            if (isCancelled()) return null;

                            Geometry result = runTrial(
                                    pixels, nChannels, type,
                                    seedX, seedY, downsample,
                                    fineSens, bestSigma, bestMorph, connectivity,
                                    edgeAware, edgeStrength,
                                    fillHoles, minHoleSize, simplifyTolerance,
                                    serverWidth, serverHeight,
                                    trialMat, trialMask, trialFloat, meanMat, stddevMat);

                            double iou = computeIoU(result, gtGeom);
                            if (iou > bestIoU) {
                                bestIoU = iou;
                                bestSens = fineSens;
                            }
                            fineTrials++;
                        }
                    }

                    logger.info("Auto-tune: best IoU={} sens={} sigma={} morph={} ({} trials)",
                            String.format("%.3f", bestIoU), String.format("%.3f", bestSens),
                            bestSigma, bestMorph, trial + fineTrials);

                    return new Result(bestSens, bestSigma, bestMorph, bestIoU, trial + fineTrials);
                }
            }
        };
    }

    // --- Trial pipeline ---

    /**
     * Run a single wand trial with the given parameters. Returns the resulting
     * geometry in image coordinates, or null if the trial produced nothing.
     */
    private static Geometry runTrial(
            byte[] pixels, int nChannels, WizardWandType type,
            double seedX, double seedY, double downsample,
            double sensitivity, double sigma, int morphKernelSize, int connectivity,
            boolean edgeAware, double edgeStrength,
            boolean fillHoles, int minHoleSize, double simplifyTolerance,
            int serverWidth, int serverHeight,
            Mat trialMat, Mat trialMask, Mat trialFloat, Mat meanMat, Mat stddevMat) {

        GeometryFactory factory = new GeometryFactory();

        // Load cached pixels into mat
        ByteBuffer buf = trialMat.createBuffer();
        buf.put(pixels);

        // Gaussian blur
        double blurSigma = Math.max(0.5, sigma);
        int bsize = (int) Math.ceil(blurSigma * 2) * 2 + 1;
        try (var blurSize = new Size(bsize, bsize)) {
            opencv_imgproc.GaussianBlur(trialMat, trialMat, blurSize, blurSigma);
        }

        // Color space conversion + threshold computation
        Mat matThreshold = trialMat;
        Scalar threshold = Scalar.all(1.0);
        int effChannels = nChannels;
        Mat labMat = null;
        Mat hsvMat = null;

        try {
            if (type == WizardWandType.LAB_DISTANCE) {
                labMat = computeLabDistanceTrial(trialMat, trialFloat, sensitivity);
                matThreshold = labMat;
                effChannels = 1;
                threshold = new Scalar(computeLabThreshold(trialMat, trialFloat, sensitivity));
            } else if (type == WizardWandType.HSV) {
                hsvMat = new Mat();
                opencv_imgproc.cvtColor(trialMat, hsvMat, opencv_imgproc.COLOR_BGR2HSV);
                matThreshold = hsvMat;
                computeStdDevThresholdTrial(hsvMat, 3, sensitivity, threshold, meanMat, stddevMat);
            } else {
                computeStdDevThresholdTrial(matThreshold, effChannels, sensitivity, threshold, meanMat, stddevMat);
            }

            // Disk mask
            int radius = (int) Math.round(W / 2.0);
            if (radius == 0) return null;
            writeDiskMask(trialMask, radius);

            // Flood fill
            Point seed = new Point(W / 2, W / 2);
            opencv_imgproc.floodFill(matThreshold, trialMask, seed, Scalar.ONE, null,
                    threshold, threshold,
                    connectivity | (2 << 8) | opencv_imgproc.FLOODFILL_MASK_ONLY | opencv_imgproc.FLOODFILL_FIXED_RANGE);
            subtractPut(trialMask, Scalar.ONE);

            // Morphological closing
            if (morphKernelSize > 0) {
                int ks = morphKernelSize;
                if (ks % 2 == 0) ks++;
                try (var strel = opencv_imgproc.getStructuringElement(
                        opencv_imgproc.MORPH_ELLIPSE, new Size(ks, ks))) {
                    opencv_imgproc.morphologyEx(trialMask, trialMask, opencv_imgproc.MORPH_CLOSE, strel);
                }
            }

            // Contour extraction
            Geometry geometry = extractContours(trialMask, factory);
            if (geometry == null) return null;

            // Transform to image space
            var transform = new AffineTransformation()
                    .scale(downsample, downsample)
                    .translate(seedX, seedY);
            geometry = transform.transform(geometry);
            geometry = GeometryTools.roundCoordinates(geometry);
            geometry = GeometryTools.constrainToBounds(geometry, 0, 0, serverWidth, serverHeight);
            if (geometry.getArea() <= 1) return null;

            // Geometry-level hole filling
            if (fillHoles) {
                if (minHoleSize <= 0) {
                    geometry = GeometryTools.fillHoles(geometry);
                } else {
                    geometry = GeometryTools.removeInteriorRings(geometry, minHoleSize);
                }
            }

            // Simplification
            if (simplifyTolerance > 0) {
                try {
                    geometry = VWSimplifier.simplify(geometry, simplifyTolerance);
                } catch (Exception ignore) { }
            }

            return geometry;
        } finally {
            if (labMat != null) labMat.close();
            if (hsvMat != null) hsvMat.close();
        }
    }

    // --- Pipeline helpers (self-contained, no dependency on WizardWandEventHandler) ---

    private static void captureRegion(QuPathViewer viewer, BufferedImage imgTemp,
                                       double x, double y, double downsample) {
        var regionStore = viewer.getImageRegionStore();
        Graphics2D g2d = imgTemp.createGraphics();
        g2d.setColor(Color.BLACK);
        g2d.setClip(0, 0, W, W);
        g2d.fillRect(0, 0, W, W);
        double xStart = Math.round(x - W * downsample * 0.5);
        double yStart = Math.round(y - W * downsample * 0.5);
        var bounds = new Rectangle2D.Double(xStart, yStart, W * downsample, W * downsample);
        g2d.scale(1.0 / downsample, 1.0 / downsample);
        g2d.translate(-xStart, -yStart);
        regionStore.paintRegion(viewer.getServer(), g2d, bounds,
                viewer.getZPosition(), viewer.getTPosition(),
                downsample, null, null, viewer.getImageDisplay());

        float opacity = viewer.getOverlayOptions().getOpacity();
        if (opacity > 0 && WizardWandParameters.getUseOverlays()) {
            ImageRegion region = ImageRegion.createInstance(
                    (int) bounds.getX() - 1, (int) bounds.getY() - 1,
                    (int) bounds.getWidth() + 2, (int) bounds.getHeight() + 2,
                    viewer.getZPosition(), viewer.getTPosition());
            if (opacity < 1)
                g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity));
            for (PathOverlay overlay : viewer.getOverlayLayers().toArray(PathOverlay[]::new)) {
                if (!(overlay instanceof HierarchyOverlay))
                    overlay.paintOverlay(g2d, region, downsample, viewer.getImageData(), true);
            }
        }
        g2d.dispose();
    }

    private static void writeDiskMask(Mat mask, int radius) {
        int width = W + 2;
        int cx = W / 2 + 1;
        int cy = W / 2 + 1;
        long r2 = (long) radius * radius;
        try (UByteIndexer idx = mask.createIndexer()) {
            for (int y = 0; y < width; y++) {
                for (int x = 0; x < width; x++) {
                    int dx = x - cx;
                    int dy = y - cy;
                    idx.put(y, x, (dx * dx + dy * dy <= r2) ? 0 : 1);
                }
            }
        }
    }

    private static void computeStdDevThresholdTrial(Mat mat, int nChannels,
                                                     double sensitivity, Scalar threshold,
                                                     Mat meanMat, Mat stddevMat) {
        meanStdDev(mat, meanMat, stddevMat);
        DoubleBuffer stddevBuf = stddevMat.createBuffer();
        double[] stddev2 = new double[nChannels];
        stddevBuf.get(stddev2);
        double scale = Math.max(sensitivity, 0.01);
        for (int i = 0; i < stddev2.length; i++)
            stddev2[i] *= scale;
        threshold.put(stddev2);
    }

    private static Mat computeLabDistanceTrial(Mat matSrc, Mat matFloat, double sensitivity) {
        matSrc.convertTo(matFloat, opencv_core.CV_32F, 1.0 / 255.0, 0.0);
        opencv_imgproc.cvtColor(matFloat, matFloat, opencv_imgproc.COLOR_BGR2Lab);

        double max = 0;
        double meanVal = 0;
        try (FloatIndexer idx = matFloat.createIndexer()) {
            int k = W / 2;
            double v1 = idx.get(k, k, 0);
            double v2 = idx.get(k, k, 1);
            double v3 = idx.get(k, k, 2);
            double meanScale = 1.0 / (W * W);
            for (int row = 0; row < W; row++) {
                for (int col = 0; col < W; col++) {
                    double L = idx.get(row, col, 0) - v1;
                    double A = idx.get(row, col, 1) - v2;
                    double B = idx.get(row, col, 2) - v3;
                    double dist = Math.sqrt(L * L + A * A + B * B);
                    if (dist > max) max = dist;
                    meanVal += dist * meanScale;
                    idx.put(row, col, 0, (float) dist);
                }
            }
        }

        Mat result = new Mat();
        opencv_core.extractChannel(matFloat, result, 0);
        if (max > 0)
            result.convertTo(result, opencv_core.CV_8U, 255.0 / max, 0);
        else
            result.convertTo(result, opencv_core.CV_8U);
        return result;
    }

    private static double computeLabThreshold(Mat matSrc, Mat matFloat, double sensitivity) {
        // Re-compute mean distance for threshold (needed separately from conversion)
        matSrc.convertTo(matFloat, opencv_core.CV_32F, 1.0 / 255.0, 0.0);
        opencv_imgproc.cvtColor(matFloat, matFloat, opencv_imgproc.COLOR_BGR2Lab);
        double meanVal = 0;
        try (FloatIndexer idx = matFloat.createIndexer()) {
            int k = W / 2;
            double v1 = idx.get(k, k, 0);
            double v2 = idx.get(k, k, 1);
            double v3 = idx.get(k, k, 2);
            double meanScale = 1.0 / (W * W);
            for (int row = 0; row < W; row++) {
                for (int col = 0; col < W; col++) {
                    double L = idx.get(row, col, 0) - v1;
                    double A = idx.get(row, col, 1) - v2;
                    double B = idx.get(row, col, 2) - v3;
                    meanVal += Math.sqrt(L * L + A * A + B * B) * meanScale;
                }
            }
        }
        return meanVal * sensitivity;
    }

    private static Geometry extractContours(Mat mask, GeometryFactory factory) {
        MatVector contours = new MatVector();
        try (Mat hierarchy = new Mat()) {
            opencv_imgproc.findContours(mask, contours, hierarchy,
                    opencv_imgproc.RETR_EXTERNAL, opencv_imgproc.CHAIN_APPROX_SIMPLE);

            List<Coordinate> coords = new ArrayList<>();
            List<Geometry> geometries = new ArrayList<>();

            for (Mat contour : contours.get()) {
                if (contour.size().height() <= 2) continue;
                coords.clear();
                try (IntIndexer idxr = contour.createIndexer()) {
                    for (long r = 0; r < idxr.size(0); r++) {
                        int px = idxr.get(r, 0L, 0L);
                        int py = idxr.get(r, 0L, 1L);
                        double xx = (px - W / 2.0 - 1);
                        double yy = (py - W / 2.0 - 1);
                        coords.add(new Coordinate(xx, yy));
                    }
                }
                if (coords.size() > 1) {
                    if (!coords.getLast().equals(coords.getFirst()))
                        coords.add(coords.getFirst());
                    var polygon = factory.createPolygon(coords.toArray(Coordinate[]::new));
                    if (coords.size() > 5 || polygon.getArea() > 1)
                        geometries.add(polygon);
                }
            }
            contours.close();

            if (geometries.isEmpty()) return null;
            var geometry = geometries.size() == 1
                    ? geometries.getFirst()
                    : GeometryCombiner.combine(geometries);
            geometry = geometry.buffer(0.5);
            return geometry;
        }
    }

    // --- IoU ---

    /**
     * Compute Intersection-over-Union between two geometries.
     */
    static double computeIoU(Geometry candidate, Geometry groundTruth) {
        if (candidate == null || candidate.isEmpty())
            return 0;
        try {
            double interArea = candidate.intersection(groundTruth).getArea();
            if (interArea == 0) return 0;
            double unionArea = candidate.getArea() + groundTruth.getArea() - interArea;
            return unionArea > 0 ? interArea / unionArea : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private WizardWandAutoTune() {}
}
