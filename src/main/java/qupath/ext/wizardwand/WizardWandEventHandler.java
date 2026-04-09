package qupath.ext.wizardwand;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.bytedeco.opencv.global.opencv_core.*;

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
import org.bytedeco.javacpp.indexer.FloatIndexer;
import org.bytedeco.javacpp.indexer.IntIndexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.scene.input.MouseEvent;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.gui.viewer.overlays.HierarchyOverlay;
import qupath.lib.gui.viewer.overlays.PathOverlay;
import qupath.lib.gui.viewer.tools.QuPathPenManager;
import qupath.lib.gui.viewer.tools.handlers.BrushToolEventHandler;
import qupath.lib.regions.ImageRegion;
import qupath.lib.roi.GeometryTools;

/**
 * Core event handler for the Wizard Wand tool.
 * <p>
 * Extends BrushToolEventHandler and reimplements the wand flood-fill logic
 * from WandToolEventHandler, adding:
 * <ul>
 *   <li>HSV color space mode</li>
 *   <li>Configurable connectivity (4 or 8)</li>
 *   <li>Configurable morphological closing kernel</li>
 *   <li>Hole filling</li>
 *   <li>Edge-aware selection</li>
 *   <li>Live geometry simplification (always-on + aggressive with Shift)</li>
 *   <li>Dwell expansion support</li>
 *   <li>Scroll-wheel sensitivity adjustment support</li>
 * </ul>
 */
public class WizardWandEventHandler extends BrushToolEventHandler {

    private static final Logger logger = LoggerFactory.getLogger(WizardWandEventHandler.class);

    // --- Sampling window size (must be odd) ---
    private static final int W = 149;

    // --- Reusable image buffers ---
    private final BufferedImage imgBGR = new BufferedImage(W, W, BufferedImage.TYPE_3BYTE_BGR);
    private final BufferedImage imgGray = new BufferedImage(W, W, BufferedImage.TYPE_BYTE_GRAY);

    // --- Reusable OpenCV objects ---
    private Mat mat = null;
    private final Mat matMask = new Mat(W + 2, W + 2, CV_8UC1);
    private final Mat matFloat = new Mat(W, W, CV_32FC3);
    private final Scalar threshold = Scalar.all(1.0);
    private final Point seed = new Point(W / 2, W / 2);
    private Mat strel = null;
    private int lastStrelSize = -1;
    private Mat contourHierarchy = null;
    private final Mat mean = new Mat();
    private final Mat stddev = new Mat();
    private final Rectangle2D bounds = new Rectangle2D.Double();
    private final Size blurSize = new Size(31, 31);
    private Mat matLabThreshold = null; // Reusable Mat for LAB distance mode
    private Mat matHsv = null;          // Reusable Mat for HSV mode (avoids corrupting shared mat)
    private final Mat emptyMat = new Mat(); // Reusable empty Mat for drawContours hierarchy arg

    // --- Drawing state ---
    private Point2D pLast = null;
    private volatile boolean drawing = false;
    private double lastDrawX, lastDrawY;
    private MouseEvent lastMouseEvent;

    // --- Dwell expansion ---
    private final DwellExpansionTimer dwellTimer;

    public WizardWandEventHandler() {
        this.dwellTimer = new DwellExpansionTimer(this);
    }

    // --- Public state accessors for scroll handler and dwell timer ---

    public boolean isDrawing() {
        return drawing;
    }

    /**
     * Stop dwell expansion timer. Called by WizardWandPathTool on deregister.
     */
    public void stopDwell() {
        dwellTimer.stopDwell();
    }

    /**
     * Re-create the shape at the last known position with current parameters.
     * Called by the scroll handler and dwell timer for live updates.
     */
    public void refreshCurrentShape() {
        if (!drawing || lastMouseEvent == null) {
            return;
        }
        // Trigger a synthetic drag event to refresh the shape
        // This uses the existing BrushToolEventHandler.mouseDragged() flow
        // which calls getUpdatedObject() -> createShape()
        mouseDragged(lastMouseEvent);
    }

    // --- Mouse event overrides for dwell lifecycle ---

    @Override
    public void mousePressed(MouseEvent e) {
        super.mousePressed(e);
        if (e.isPrimaryButtonDown() && !e.isConsumed()) {
            drawing = true;
            var viewer = getViewer();
            if (viewer != null) {
                var p = viewer.componentPointToImagePoint(e.getX(), e.getY(), null, false);
                lastDrawX = p.getX();
                lastDrawY = p.getY();
                lastMouseEvent = e;
                dwellTimer.startDwell(lastDrawX, lastDrawY);
            }
        }
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        if (drawing) {
            lastMouseEvent = e;
            var viewer = getViewer();
            if (viewer != null) {
                var p = viewer.componentPointToImagePoint(e.getX(), e.getY(), null, false);
                lastDrawX = p.getX();
                lastDrawY = p.getY();
                dwellTimer.onMouseMove(lastDrawX, lastDrawY);
            }
        }
        super.mouseDragged(e);
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        drawing = false;
        dwellTimer.stopDwell();
        lastMouseEvent = null;
        pLast = null;
        super.mouseReleased(e);
    }

    // --- Core wand logic ---

    @Override
    protected Geometry createShape(MouseEvent e, double x, double y, boolean useTiles, Geometry addToShape) {

        GeometryFactory factory = getGeometryFactory();

        // Skip if position hasn't changed enough
        if (addToShape != null && pLast != null && pLast.distanceSq(x, y) < 2)
            return null;

        long startTime = System.currentTimeMillis();

        QuPathViewer viewer = getViewer();
        if (viewer == null)
            return null;

        double downsample = Math.max(1, Math.round(viewer.getDownsampleFactor() * 4)) / 4.0;

        // --- Stage 1: Image Capture ---
        var type = WizardWandParameters.getWandType();
        boolean doGray = type == WizardWandType.GRAY;
        BufferedImage imgTemp = doGray ? imgGray : imgBGR;
        int nChannels = doGray ? 1 : 3;

        captureRegion(viewer, imgTemp, x, y, downsample);

        // --- Transfer pixels to OpenCV Mat ---
        if (mat != null && (mat.channels() != nChannels || mat.depth() != opencv_core.CV_8U)) {
            mat.close();
            mat = null;
        }
        if (mat == null || mat.isNull() || mat.empty())
            mat = new Mat(W, W, CV_8UC(nChannels));

        byte[] buffer = ((DataBufferByte) imgTemp.getRaster().getDataBuffer()).getData();
        ByteBuffer matBuffer = mat.createBuffer();
        matBuffer.put(buffer);

        // --- Ctrl = simple selection (exact match, no smoothing) ---
        boolean doSimpleSelection = e.isShortcutDown() && !e.isShiftDown();

        if (doSimpleSelection) {
            matMask.put(Scalar.ZERO);
            int conn = WizardWandParameters.getConnectivity();
            opencv_imgproc.floodFill(mat, matMask, seed, Scalar.ONE, null,
                    Scalar.ZERO, Scalar.ZERO,
                    conn | (2 << 8) | opencv_imgproc.FLOODFILL_MASK_ONLY | opencv_imgproc.FLOODFILL_FIXED_RANGE);
            subtractPut(matMask, Scalar.ONE);
        } else {

            // --- Stage 3: Pre-processing (Gaussian blur) ---
            double blurSigma = Math.max(0.5, WizardWandParameters.getSigma());
            int size = (int) Math.ceil(blurSigma * 2) * 2 + 1;
            blurSize.width(size);
            blurSize.height(size);
            opencv_imgproc.GaussianBlur(mat, mat, blurSize, blurSigma);

            // --- Stage 2+4: Color Space Conversion + Threshold computation ---
            Mat matThreshold = mat;
            double effectiveSensitivity = WizardWandParameters.getEffectiveSensitivity();

            if (type == WizardWandType.LAB_DISTANCE) {
                matThreshold = computeLabDistance(mat, effectiveSensitivity);
                nChannels = 1;
            } else if (type == WizardWandType.HSV) {
                matThreshold = computeHsvThreshold(mat, effectiveSensitivity);
                nChannels = 3;
            } else {
                computeStdDevThreshold(matThreshold, nChannels, effectiveSensitivity);
            }

            // --- Stage 4: Edge-Aware Mode (optional) ---
            if (WizardWandParameters.getEdgeAware()) {
                applyEdgeBarrier(matThreshold, nChannels);
            }

            // --- Stage 5: Flood Fill ---
            int radius = (int) Math.round(W / 2.0 * QuPathPenManager.getPenManager().getPressure());
            if (radius == 0)
                return null;

            matMask.put(Scalar.ZERO);
            opencv_imgproc.circle(matMask, seed, radius, Scalar.ONE);

            int conn = WizardWandParameters.getConnectivity();
            opencv_imgproc.floodFill(matThreshold, matMask, seed, Scalar.ONE, null,
                    threshold, threshold,
                    conn | (2 << 8) | opencv_imgproc.FLOODFILL_MASK_ONLY | opencv_imgproc.FLOODFILL_FIXED_RANGE);
            subtractPut(matMask, Scalar.ONE);

            // --- Stage 6: Morphological Closing ---
            int kernelSize = WizardWandParameters.getMorphKernelSize();
            if (kernelSize > 0) {
                // Ensure odd kernel size
                if (kernelSize % 2 == 0)
                    kernelSize++;
                if (strel == null || lastStrelSize != kernelSize) {
                    if (strel != null)
                        strel.close();
                    strel = opencv_imgproc.getStructuringElement(
                            opencv_imgproc.MORPH_ELLIPSE, new Size(kernelSize, kernelSize));
                    lastStrelSize = kernelSize;
                }
                opencv_imgproc.morphologyEx(matMask, matMask, opencv_imgproc.MORPH_CLOSE, strel);
            }

            // --- Stage 7: Hole Filling ---
            if (WizardWandParameters.getFillHoles()) {
                fillHoles(matMask);
            }
        }

        // --- Stage 8: Contour Extraction -> JTS Geometry ---
        Geometry geometry = extractContours(factory);
        if (geometry == null)
            return null;

        // --- Stage 9: Transform to Image Space ---
        var transform = new AffineTransformation()
                .scale(downsample, downsample)
                .translate(x, y);
        geometry = transform.transform(geometry);
        geometry = GeometryTools.roundCoordinates(geometry);
        geometry = GeometryTools.constrainToBounds(geometry, 0, 0,
                viewer.getServerWidth(), viewer.getServerHeight());
        if (geometry.getArea() <= 1)
            return null;

        // --- Live Simplification ---
        double tolerance = e.isShiftDown()
                ? WizardWandParameters.getAggressiveSimplifyTolerance()
                : WizardWandParameters.getSimplifyTolerance();
        if (tolerance > 0) {
            try {
                geometry = VWSimplifier.simplify(geometry, tolerance);
            } catch (Exception ex) {
                logger.debug("Error simplifying geometry: {}", ex.getMessage());
            }
        }

        long endTime = System.currentTimeMillis();
        logger.trace("{} time: {}ms", getClass().getSimpleName(), endTime - startTime);

        if (pLast == null)
            pLast = new Point2D.Double(x, y);
        else
            pLast.setLocation(x, y);

        return geometry;
    }

    // --- Pipeline Stage Methods ---

    /**
     * Stage 1: Capture the image region centered on (x, y) into the provided BufferedImage.
     */
    private void captureRegion(QuPathViewer viewer, BufferedImage imgTemp, double x, double y, double downsample) {
        var regionStore = viewer.getImageRegionStore();

        Graphics2D g2d = imgTemp.createGraphics();
        g2d.setColor(Color.BLACK);
        g2d.setClip(0, 0, W, W);
        g2d.fillRect(0, 0, W, W);
        double xStart = Math.round(x - W * downsample * 0.5);
        double yStart = Math.round(y - W * downsample * 0.5);
        bounds.setFrame(xStart, yStart, W * downsample, W * downsample);
        g2d.scale(1.0 / downsample, 1.0 / downsample);
        g2d.translate(-xStart, -yStart);
        regionStore.paintRegion(viewer.getServer(), g2d, bounds,
                viewer.getZPosition(), viewer.getTPosition(),
                downsample, null, null, viewer.getImageDisplay());

        // Optionally include overlay information
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

    /**
     * Stage 2: Compute LAB distance from seed pixel, set threshold.
     * Returns a single-channel matThreshold that should be used for flood fill.
     */
    private Mat computeLabDistance(Mat matSrc, double effectiveSensitivity) {
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
                    if (dist > max)
                        max = dist;
                    meanVal += dist * meanScale;
                    idx.put(row, col, 0, (float) dist);
                }
            }
        }

        if (matLabThreshold == null)
            matLabThreshold = new Mat();
        opencv_core.extractChannel(matFloat, matLabThreshold, 0);
        if (max > 0)
            matLabThreshold.convertTo(matLabThreshold, opencv_core.CV_8U, 255.0 / max, 0);
        else
            matLabThreshold.convertTo(matLabThreshold, opencv_core.CV_8U);
        threshold.put(meanVal * effectiveSensitivity);

        return matLabThreshold;
    }

    /**
     * Stage 2: Convert to HSV and compute threshold from stddev.
     * Returns the HSV mat for multi-channel flood fill.
     * Uses a separate Mat to avoid corrupting the shared field-level mat.
     */
    private Mat computeHsvThreshold(Mat matSrc, double effectiveSensitivity) {
        if (matHsv == null)
            matHsv = new Mat();
        // Convert to a separate Mat to preserve the original BGR data in the shared field
        opencv_imgproc.cvtColor(matSrc, matHsv, opencv_imgproc.COLOR_BGR2HSV);

        // Use standard deviation-based threshold like RGB mode
        computeStdDevThreshold(matHsv, 3, effectiveSensitivity);
        return matHsv;
    }

    /**
     * Stage 2: Compute threshold from standard deviation (for RGB, GRAY, HSV modes).
     */
    private void computeStdDevThreshold(Mat matThreshold, int nChannels, double effectiveSensitivity) {
        meanStdDev(matThreshold, mean, stddev);
        DoubleBuffer stddevBuffer = stddev.createBuffer();
        double[] stddev2 = new double[nChannels];
        stddevBuffer.get(stddev2);
        double scale = 1.0 / effectiveSensitivity;
        if (scale < 0)
            scale = 0.01;
        for (int i = 0; i < stddev2.length; i++)
            stddev2[i] = stddev2[i] * scale;
        threshold.put(stddev2);
    }

    /**
     * Stage 4: Apply edge barrier to suppress flood fill across strong gradients.
     * Computes Sobel gradient magnitude and adds it as an intensity penalty.
     */
    private void applyEdgeBarrier(Mat matThreshold, int nChannels) {
        double edgeStr = WizardWandParameters.getEdgeStrength();
        if (edgeStr <= 0)
            return;

        // Convert to grayscale for edge detection if multi-channel
        Mat gray;
        boolean needClose = false;
        if (nChannels == 1) {
            gray = matThreshold;
        } else {
            gray = new Mat();
            opencv_imgproc.cvtColor(matThreshold, gray, opencv_imgproc.COLOR_BGR2GRAY);
            needClose = true;
        }

        // Compute Sobel gradient magnitude
        try (Mat gradX = new Mat(); Mat gradY = new Mat(); Mat gradMag = new Mat()) {
            opencv_imgproc.Sobel(gray, gradX, CV_32F, 1, 0);
            opencv_imgproc.Sobel(gray, gradY, CV_32F, 0, 1);
            opencv_core.magnitude(gradX, gradY, gradMag);

            // Normalize to 0-255
            double[] minVal = new double[1];
            double[] maxVal = new double[1];
            opencv_core.minMaxLoc(gradMag, minVal, maxVal, null, null, null);
            if (maxVal[0] > 0) {
                gradMag.convertTo(gradMag, CV_8U, 255.0 * edgeStr / maxVal[0], 0);

                // Add gradient as intensity penalty to each channel
                // This creates artificial barriers at strong edges
                if (nChannels == 1) {
                    opencv_core.add(matThreshold, gradMag, matThreshold);
                } else {
                    // Add to each channel individually
                    try (Mat gradMag3 = new Mat()) {
                        MatVector channels = new MatVector(3);
                        channels.put(0, gradMag);
                        channels.put(1, gradMag);
                        channels.put(2, gradMag);
                        opencv_core.merge(channels, gradMag3);
                        opencv_core.add(matThreshold, gradMag3, matThreshold);
                        channels.close();
                    }
                }
            }
        } finally {
            if (needClose)
                gray.close();
        }
    }

    /**
     * Stage 7: Fill enclosed holes in the mask using flood-fill from corner technique.
     * Only fills holes smaller than the configured minimum hole size.
     */
    private void fillHoles(Mat mask) {
        int minHoleSize = WizardWandParameters.getMinHoleSize();

        // The mask is (W+2) x (W+2). Work on the inner region.
        // Strategy: flood fill from corner of an inverted copy.
        // Pixels NOT reached by the fill are interior holes.
        try (Mat inverted = new Mat(); Mat cornerFill = new Mat()) {
            // Invert the mask: foreground (1) -> 0, background (0) -> 1
            opencv_core.bitwise_not(mask, inverted);

            // Flood fill from (0,0) on the inverted mask to find the external background
            inverted.copyTo(cornerFill);
            try (Mat fillMask = new Mat(mask.rows() + 2, mask.cols() + 2, CV_8UC1)) {
                fillMask.put(Scalar.ZERO);
                opencv_imgproc.floodFill(cornerFill, fillMask, new Point(0, 0), new Scalar(0));
            }

            // cornerFill now has 0 where the external background was, and non-zero for holes
            // If minHoleSize > 0, we need to check hole sizes before filling
            if (minHoleSize > 0) {
                try (MatVector holeContours = new MatVector(); Mat holeHierarchy = new Mat()) {
                    opencv_imgproc.findContours(cornerFill, holeContours, holeHierarchy,
                            opencv_imgproc.RETR_EXTERNAL, opencv_imgproc.CHAIN_APPROX_SIMPLE);
                    for (Mat contour : holeContours.get()) {
                        double area = opencv_imgproc.contourArea(contour);
                        if (area < minHoleSize) {
                            // Fill this small hole in the original mask
                            try (MatVector single = new MatVector(1)) {
                                single.put(0, contour);
                                opencv_imgproc.drawContours(mask, single, 0,
                                        Scalar.all(1), opencv_imgproc.FILLED,
                                        opencv_imgproc.LINE_8, emptyMat, Integer.MAX_VALUE,
                                        new Point(0, 0));
                            }
                        }
                    }
                    holeContours.close();
                }
            } else {
                // Fill ALL holes: OR the hole mask with the original
                opencv_core.bitwise_or(mask, cornerFill, mask);
            }
        }
    }

    /**
     * Stage 8: Extract contours from the mask and convert to JTS Geometry.
     */
    private Geometry extractContours(GeometryFactory factory) {
        MatVector contours = new MatVector();
        if (contourHierarchy == null)
            contourHierarchy = new Mat();

        opencv_imgproc.findContours(matMask, contours, contourHierarchy,
                opencv_imgproc.RETR_EXTERNAL, opencv_imgproc.CHAIN_APPROX_SIMPLE);

        List<Coordinate> coords = new ArrayList<>();
        List<Geometry> geometries = new ArrayList<>();

        for (Mat contour : contours.get()) {
            // Discard single pixels / lines
            if (contour.size().height() <= 2)
                continue;

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
                // Ensure closed
                if (!coords.getLast().equals(coords.getFirst()))
                    coords.add(coords.getFirst());
                var polygon = factory.createPolygon(coords.toArray(Coordinate[]::new));
                if (coords.size() > 5 || polygon.getArea() > 1)
                    geometries.add(polygon);
            }
        }
        contours.close();

        if (geometries.isEmpty())
            return null;

        // Handle OpenCV contour pixel-center offset by dilating boundary
        var geometry = geometries.size() == 1
                ? geometries.getFirst()
                : GeometryCombiner.combine(geometries);
        geometry = geometry.buffer(0.5);

        return geometry;
    }

    /**
     * Don't need the diameter for calculations, but it's helpful for setting the cursor.
     */
    @Override
    protected double getBrushDiameter() {
        QuPathViewer viewer = getViewer();
        if (viewer == null)
            return W / 8.0;
        else
            return W * viewer.getDownsampleFactor() / 8.0;
    }

}
