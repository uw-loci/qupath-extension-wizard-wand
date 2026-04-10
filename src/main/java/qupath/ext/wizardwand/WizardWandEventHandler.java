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
import org.bytedeco.javacpp.indexer.UByteIndexer;
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
    // Dedicated edge-capture buffer used when edge pyramid offset > 0
    private final BufferedImage imgEdge = new BufferedImage(W, W, BufferedImage.TYPE_BYTE_GRAY);

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
    private Mat matEdge = null;         // Reusable Mat for dedicated edge capture (pyramid offset > 0)
    private final Mat emptyMat = new Mat(); // Reusable empty Mat for drawContours hierarchy arg

    // --- Drawing state ---
    private Point2D pLast = null;
    private volatile boolean drawing = false;
    private boolean forceRefresh = false; // Set by scroll handler to bypass position check
    private boolean firstUpdateOfStroke = false; // Set on mousePressed, cleared after first mask sanity log
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
     * Reset all drawing state. Called by WizardWandPathTool on deregister
     * to ensure clean state if the user switches tools mid-draw.
     */
    public void resetDrawingState() {
        drawing = false;
        forceRefresh = false;
        firstUpdateOfStroke = false;
        lastMouseEvent = null;
        pLast = null;
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
        // Set flag to bypass the position-unchanged early return in createShape()
        forceRefresh = true;
        try {
            mouseDragged(lastMouseEvent);
        } finally {
            forceRefresh = false;
        }
    }

    // --- Mouse event overrides for dwell lifecycle ---

    @Override
    public void mousePressed(MouseEvent e) {
        super.mousePressed(e);
        if (e.isPrimaryButtonDown() && !e.isConsumed()) {
            drawing = true;
            firstUpdateOfStroke = true;
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
        firstUpdateOfStroke = false;
        dwellTimer.stopDwell();
        lastMouseEvent = null;
        pLast = null;

        // Apply final hole filling to the complete annotation before committing.
        // This catches holes formed by union of multiple drag strokes that
        // the per-stroke fill in createShape() cannot prevent.
        if (WizardWandParameters.getFillHoles()) {
            var viewer = getViewer();
            if (viewer != null) {
                var selected = viewer.getSelectedObject();
                if (selected != null && selected.isAnnotation() && selected.isEditable()
                        && selected.hasROI() && selected.getROI().isArea()) {
                    var roi = selected.getROI();
                    var geom = roi.getGeometry();
                    int minHoleSize = WizardWandParameters.getMinHoleSize();
                    Geometry filled;
                    if (minHoleSize <= 0) {
                        filled = GeometryTools.fillHoles(geom);
                    } else {
                        filled = GeometryTools.removeInteriorRings(geom, minHoleSize);
                    }
                    if (filled != geom && !filled.isEmpty()) {
                        var newRoi = GeometryTools.geometryToROI(filled, roi.getImagePlane());
                        ((qupath.lib.objects.PathAnnotationObject) selected).setROI(newRoi);
                    }
                }
            }
        }

        super.mouseReleased(e);
    }

    // --- Core wand logic ---

    @Override
    protected Geometry createShape(MouseEvent e, double x, double y, boolean useTiles, Geometry addToShape) {

        GeometryFactory factory = getGeometryFactory();

        // Skip if position hasn't changed enough -- BUT allow re-creation when
        // forceRefresh is set (scroll wheel or dwell timer triggered a refresh)
        if (addToShape != null && pLast != null && pLast.distanceSq(x, y) < 2 && !forceRefresh)
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

        // One-shot diagnostic per stroke (at WARN level so it surfaces without
        // logback changes). Gives a per-stage readout of the mask and the final
        // geometry so we can tell exactly where the "top-left curved, rest
        // square" bug is being introduced.
        boolean diagnose = firstUpdateOfStroke;

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
            // --- Stage 5: Flood Fill ---
            int radius = (int) Math.round(W / 2.0 * QuPathPenManager.getPenManager().getPressure());
            if (radius == 0)
                return null;

            // Initialize the flood fill mask to a filled-disk pattern.
            //
            // We write the mask bytes directly rather than relying on
            // Mat.put(Scalar) + opencv_imgproc.circle(FILLED). That combination
            // was producing intermittent square selections (the whole sampling
            // window filling) that strongly suggest either the Scalar fill or
            // the filled-circle draw wasn't actually taking effect. Direct byte
            // writing removes all ambiguity.
            //
            // Mask layout: everything = 1 (barrier), except pixels inside a disk
            // centered at (W/2, W/2) with the current radius, which become 0
            // (fillable). The disk center matches the `seed` used for flood fill.
            writeDiskMask(radius);

            if (diagnose) {
                logMaskStats("afterWriteDisk", radius);
            }

            // --- Stage 4: Edge-Aware barriers (optional, applied to mask) ---
            // Mark strong edge pixels as barriers in the mask BEFORE flood fill.
            // Non-zero mask pixels prevent floodFill from crossing them.
            if (WizardWandParameters.getEdgeAware()) {
                applyEdgeBarriersToMask(matThreshold, nChannels, viewer, x, y, downsample);
                if (diagnose) {
                    logMaskStats("afterEdgeBarriers", radius);
                }
            }

            int conn = WizardWandParameters.getConnectivity();
            opencv_imgproc.floodFill(matThreshold, matMask, seed, Scalar.ONE, null,
                    threshold, threshold,
                    conn | (2 << 8) | opencv_imgproc.FLOODFILL_MASK_ONLY | opencv_imgproc.FLOODFILL_FIXED_RANGE);
            if (diagnose) {
                logMaskStats("afterFloodFill", radius);
            }
            subtractPut(matMask, Scalar.ONE);
            if (diagnose) {
                logMaskStats("afterSubtract", radius);
            }

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
                if (diagnose) {
                    logMaskStats("afterMorph", radius);
                }
            }

            // Note: mask-level hole filling was removed. When the flood-fill
            // disk is tangent to the mask edges (radius ~= W/2), the "outside
            // the disk" region is split into four disconnected corners and the
            // corner-flood-fill-from-(0,0) heuristic used by fillHoles(Mat)
            // fails to identify the three non-TL corners as "external" --
            // causing them to be classified as holes and filled back into the
            // mask, producing the "top-left curved, three sides square"
            // selection bug. Hole filling is now handled exclusively on the
            // JTS geometry below via GeometryTools.removeInteriorRings /
            // GeometryTools.fillHoles, which operates on proper polygonal
            // holes (interior rings) and is correct by construction.

        }

        // --- Stage 8: Contour Extraction -> JTS Geometry ---
        Geometry geometry = extractContours(factory);
        if (diagnose) {
            if (geometry == null) {
                logger.debug("WizardWand[extractContours] geometry=null");
            } else {
                var env = geometry.getEnvelopeInternal();
                double area = geometry.getArea();
                double envArea = env.getWidth() * env.getHeight();
                double ratio = envArea > 0 ? area / envArea : 0;
                logger.debug("WizardWand[extractContours] geomType={} numGeoms={} area={} envelope=[{},{},{},{}] areaRatio={} (1.0=square, ~0.785=circle)",
                        geometry.getGeometryType(), geometry.getNumGeometries(),
                        String.format("%.1f", area),
                        String.format("%.1f", env.getMinX()), String.format("%.1f", env.getMinY()),
                        String.format("%.1f", env.getMaxX()), String.format("%.1f", env.getMaxY()),
                        String.format("%.3f", ratio));
            }
        }
        if (geometry == null) {
            if (firstUpdateOfStroke)
                firstUpdateOfStroke = false;
            return null;
        }

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

        // --- Geometry-level Hole Filling ---
        // This catches holes that the mask-level fill misses, including holes
        // formed during geometry union operations when dragging
        if (WizardWandParameters.getFillHoles()) {
            int minHoleSize = WizardWandParameters.getMinHoleSize();
            if (minHoleSize <= 0) {
                geometry = GeometryTools.fillHoles(geometry);
            } else {
                geometry = GeometryTools.removeInteriorRings(geometry, minHoleSize);
            }
        }

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

        if (diagnose) {
            var env = geometry.getEnvelopeInternal();
            double area = geometry.getArea();
            double envArea = env.getWidth() * env.getHeight();
            double ratio = envArea > 0 ? area / envArea : 0;
            logger.debug("WizardWand[finalGeometry] area={} envelope=[{},{},{},{}] areaRatio={}",
                    String.format("%.1f", area),
                    String.format("%.1f", env.getMinX()), String.format("%.1f", env.getMinY()),
                    String.format("%.1f", env.getMaxX()), String.format("%.1f", env.getMaxY()),
                    String.format("%.3f", ratio));
            firstUpdateOfStroke = false;
        }

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
    /**
     * Initialize the flood fill mask to a filled-disk pattern for the given radius.
     * Outside the disk: 1 (barrier). Inside: 0 (fillable). The disk is centered at
     * mask coordinates (W/2 + 1, W/2 + 1) -- i.e. the mask pixel that corresponds
     * to image pixel (W/2, W/2), which is the flood fill seed.
     * <p>
     * Uses a UByteIndexer, which is the idiomatic JavaCPP pattern for per-pixel
     * writes into an OpenCV Mat (see {@code OpenCVTools.putPixelsUnsigned}).
     * Earlier versions tried {@code circle(FILLED)}, linear {@code createBuffer().put()},
     * and {@code Mat.ptr(row).put(byte[])}; all exhibited intermittent or systematic
     * failures (notably: disk only materializing in the top-left quadrant) because
     * they did not reliably honor the Mat's row stride. The indexer owns that
     * stride accounting.
     */
    private void writeDiskMask(int radius) {
        int width = W + 2;            // 151
        int cx = W / 2 + 1;            // disk center matches seed in mask coords
        int cy = W / 2 + 1;
        long r2 = (long) radius * radius;
        try (UByteIndexer idx = matMask.createIndexer()) {
            for (int y = 0; y < width; y++) {
                for (int x = 0; x < width; x++) {
                    int dx = x - cx;
                    int dy = y - cy;
                    idx.put(y, x, (dx * dx + dy * dy <= r2) ? 0 : 1);
                }
            }
        }
    }

    /**
     * Per-quadrant pixel count of the mask at a given pipeline stage, logged at
     * DEBUG level (guarded so the full-mask scan is skipped when debug is off).
     * Counts zero vs non-zero pixels in each quadrant of the {@code matMask}
     * (split at the disk center). If the disk is correctly set up, the four
     * quadrants should have identical non-zero counts. Any asymmetry points at
     * the stage that introduced the bug.
     */
    private void logMaskStats(String stage, int radius) {
        if (!logger.isDebugEnabled())
            return;
        int cx = W / 2 + 1;
        int cy = W / 2 + 1;
        int[] nonzero = new int[4]; // TL, TR, BL, BR
        int[] total = new int[4];
        int[] uniqueValSample = new int[4];
        try (UByteIndexer idx = matMask.createIndexer()) {
            for (int y = 0; y < W + 2; y++) {
                int qy = (y < cy) ? 0 : 2;
                for (int x = 0; x < W + 2; x++) {
                    int q = qy + ((x < cx) ? 0 : 1);
                    int v = idx.get(y, x);
                    total[q]++;
                    if (v != 0) {
                        nonzero[q]++;
                        uniqueValSample[q] = v;
                    }
                }
            }
        }
        logger.debug("WizardWand[{}] r={} step={} nonzero/total TL={}/{} TR={}/{} BL={}/{} BR={}/{} sampleVal[TL,TR,BL,BR]=[{},{},{},{}]",
                stage, radius, matMask.step(0),
                nonzero[0], total[0],
                nonzero[1], total[1],
                nonzero[2], total[2],
                nonzero[3], total[3],
                uniqueValSample[0], uniqueValSample[1], uniqueValSample[2], uniqueValSample[3]);
    }

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
     * <p>
     * Higher sensitivity = larger threshold = bigger selection (consistent with LAB mode).
     * Note: the original QuPath wand uses 1/sensitivity here, which inverts the
     * relationship. We use sensitivity directly so that dwell expansion, scroll wheel,
     * and presets all work intuitively (higher = larger selection across ALL modes).
     */
    private void computeStdDevThreshold(Mat matThreshold, int nChannels, double effectiveSensitivity) {
        meanStdDev(matThreshold, mean, stddev);
        DoubleBuffer stddevBuffer = stddev.createBuffer();
        double[] stddev2 = new double[nChannels];
        stddevBuffer.get(stddev2);
        double scale = effectiveSensitivity;
        if (scale < 0)
            scale = 0.01;
        for (int i = 0; i < stddev2.length; i++)
            stddev2[i] = stddev2[i] * scale;
        threshold.put(stddev2);
    }

    /**
     * Stage 4: Mark strong edge pixels as barriers in the flood fill mask.
     * <p>
     * Non-zero pixels in matMask act as barriers for floodFill. We compute
     * Sobel gradient magnitude, threshold it, and set barrier pixels to 1
     * in the mask. The seed pixel is always kept clear so flood fill can start.
     * <p>
     * Two modes controlled by preferences:
     * <ul>
     *   <li><b>edgePyramidOffset = 0</b>: edges computed on the fill patch itself
     *       (existing behavior, zoom-coupled).</li>
     *   <li><b>edgePyramidOffset &gt; 0</b>: second capture at a coarser pyramid
     *       level, Sobel on that, then crop the central region corresponding to
     *       the fill patch and resize back to WxW. Gives zoom-stable structural
     *       edges that don't collapse into cellular texture at high zoom.</li>
     * </ul>
     * <p>
     * Normalization modes:
     * <ul>
     *   <li>RELATIVE: threshold = (1 - edgeStr) * max(gradient) in the window.</li>
     *   <li>ABSOLUTE: threshold = (1 - edgeStr) * 255 on a fixed-range gradient,
     *       for consistent behavior across scenes and zooms.</li>
     * </ul>
     */
    private void applyEdgeBarriersToMask(Mat matThreshold, int nChannels,
                                          QuPathViewer viewer, double x, double y, double fillDs) {
        double edgeStr = WizardWandParameters.getEdgeStrength();
        if (edgeStr <= 0)
            return;

        int offset = WizardWandParameters.getEdgePyramidOffset();
        EdgeNormalizationMode mode = WizardWandParameters.getEdgeNormalizationMode();

        // Clamp offset so the central cropped region after resize is at least 8x8
        int maxOffsetForSize = 0;
        while ((W >> (maxOffsetForSize + 1)) >= 8)
            maxOffsetForSize++;
        if (offset > maxOffsetForSize) {
            logger.debug("Clamping edge pyramid offset from {} to {} (W={})",
                    offset, maxOffsetForSize, W);
            offset = maxOffsetForSize;
        }

        // Choose the source Mat for edge detection
        Mat edgeSource = null;
        boolean closeEdgeSource = false;

        if (offset > 0 && viewer != null) {
            // Capture a second patch at a coarser pyramid level
            double rawEdgeDs = fillDs * (1 << offset);
            double edgeDs = Math.max(1, Math.round(rawEdgeDs * 4)) / 4.0;

            try {
                captureRegion(viewer, imgEdge, x, y, edgeDs);

                if (matEdge == null || matEdge.isNull() || matEdge.empty()
                        || matEdge.channels() != 1 || matEdge.depth() != opencv_core.CV_8U) {
                    if (matEdge != null)
                        matEdge.close();
                    matEdge = new Mat(W, W, CV_8UC1);
                }
                byte[] edgeBuffer = ((DataBufferByte) imgEdge.getRaster().getDataBuffer()).getData();
                ByteBuffer mbuf = matEdge.createBuffer();
                mbuf.put(edgeBuffer);
                edgeSource = matEdge;
            } catch (Exception ex) {
                logger.debug("Edge capture at offset {} failed, falling back to fill patch: {}",
                        offset, ex.getMessage());
                offset = 0;
                edgeSource = null;
            }
        }

        // Fallback / offset==0: edge detection on the fill patch
        if (edgeSource == null) {
            if (nChannels == 1) {
                edgeSource = matThreshold;
            } else {
                edgeSource = new Mat();
                opencv_imgproc.cvtColor(matThreshold, edgeSource, opencv_imgproc.COLOR_BGR2GRAY);
                closeEdgeSource = true;
            }
        }

        try (Mat gradX = new Mat(); Mat gradY = new Mat(); Mat gradMag = new Mat(); Mat edgeMask = new Mat()) {
            opencv_imgproc.Sobel(edgeSource, gradX, CV_32F, 1, 0);
            opencv_imgproc.Sobel(edgeSource, gradY, CV_32F, 0, 1);
            opencv_core.magnitude(gradX, gradY, gradMag);

            double gradThreshold;
            if (mode == EdgeNormalizationMode.ABSOLUTE) {
                // Absolute threshold on 0-255 scale. Sobel on uint8 input can
                // produce magnitudes up to ~360, but we clamp to 255 on convertTo.
                gradThreshold = (1.0 - edgeStr) * 255.0;
                gradMag.convertTo(gradMag, CV_8U);
            } else {
                // RELATIVE (default): threshold relative to the max in this window
                double[] minVal = new double[1];
                double[] maxVal = new double[1];
                opencv_core.minMaxLoc(gradMag, minVal, maxVal, null, null, null);
                if (maxVal[0] <= 0) {
                    return; // No gradient -> no barriers
                }
                gradThreshold = (1.0 - edgeStr) * maxVal[0];
            }

            opencv_imgproc.threshold(gradMag, edgeMask, gradThreshold, 1.0,
                    opencv_imgproc.THRESH_BINARY);
            edgeMask.convertTo(edgeMask, CV_8U);

            // When offset > 0: the edge mask was computed on a larger image-space
            // region than the fill patch. Crop the central Wc x Wc region (which
            // corresponds exactly to the fill patch in image space) and resize
            // back to WxW using nearest-neighbor (it's a binary mask).
            Mat finalMask = edgeMask;
            Mat resizedMask = null;
            try {
                if (offset > 0) {
                    int wc = W >> offset;
                    if (wc < 1) wc = 1;
                    int cropX = (W - wc) / 2;
                    int cropY = (W - wc) / 2;
                    try (var rect = new org.bytedeco.opencv.opencv_core.Rect(cropX, cropY, wc, wc);
                         Mat cropped = new Mat(edgeMask, rect)) {
                        resizedMask = new Mat();
                        opencv_imgproc.resize(cropped, resizedMask, new Size(W, W),
                                0, 0, opencv_imgproc.INTER_NEAREST);
                        finalMask = resizedMask;
                    }
                }

                // Write edge barriers into the flood fill mask at offset (1,1)
                // matMask is (W+2)x(W+2), finalMask is WxW
                for (int row = 0; row < W; row++) {
                    for (int col = 0; col < W; col++) {
                        if (finalMask.ptr(row, col).get() != 0) {
                            matMask.ptr(row + 1, col + 1).put((byte) 1);
                        }
                    }
                }

                // Always keep the seed pixel clear so flood fill can start.
                // The seed must be 0 in the mask -- non-zero means "barrier" and
                // floodFill will reject the seed entirely, filling nothing.
                matMask.ptr(W / 2 + 1, W / 2 + 1).put((byte) 0);
            } finally {
                if (resizedMask != null)
                    resizedMask.close();
            }
        } finally {
            if (closeEdgeSource && edgeSource != null)
                edgeSource.close();
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

        if (firstUpdateOfStroke) {
            long nContours = contours.size();
            logger.debug("WizardWand[findContours] count={}", nContours);
            for (long i = 0; i < nContours; i++) {
                var c = contours.get(i);
                double area = opencv_imgproc.contourArea(c);
                var bb = opencv_imgproc.boundingRect(c);
                logger.debug("WizardWand[contour {}] points={} area={} bbox=[{},{} {}x{}]",
                        i, c.size().height(),
                        String.format("%.1f", area),
                        bb.x(), bb.y(), bb.width(), bb.height());
            }
        }

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
