package com.example.scanner.engine

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max

object OpenCvDocumentProcessor {

    data class Quadrilateral(
        val topLeft: Point,
        val topRight: Point,
        val bottomRight: Point,
        val bottomLeft: Point
    ) {
        fun toArray(): Array<Point> = arrayOf(topLeft, topRight, bottomRight, bottomLeft)
    }

    /**
     * 1. Detect 4 corners of a notebook/paper on a complex textured background (e.g. bedsheet)
     */
    fun detectNotebookCorners(bitmap: Bitmap): Quadrilateral {
        val originalMat = Mat()
        Utils.bitmapToMat(bitmap, originalMat)

        // Downscale image to a normalized resolution (max dim 800) for real-time speed & noise suppression
        val maxDim = 800.0
        val scale = maxDim / max(originalMat.cols(), originalMat.rows()).toDouble()
        val scaledMat = Mat()
        Imgproc.resize(originalMat, scaledMat, Size(originalMat.cols() * scale, originalMat.rows() * scale))

        /*
         * WHY BEDSHEETS CAUSE FALSE EDGES:
         * Standard Canny on grayscale captures high-frequency fabric patterns, seams, and prints.
         *
         * SOLUTION:
         * Convert to HSV. Notebook paper is white/cream (very low saturation, high brightness).
         * Masking out colored/patterned background eliminates bedsheet textures before edge detection.
         */
        val hsvMat = Mat()
        Imgproc.cvtColor(scaledMat, hsvMat, Imgproc.COLOR_BGR2HSV)

        val paperMask = Mat()
        // Paper threshold: Saturation: 0..60, Value: 130..255
        Core.inRange(
            hsvMat,
            Scalar(0.0, 0.0, 130.0),
            Scalar(180.0, 60.0, 255.0),
            paperMask
        )

        // Morphological closing to seal internal ruled lines/handwriting into a solid polygon
        val closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
        Imgproc.morphologyEx(paperMask, paperMask, Imgproc.MORPH_CLOSE, closeKernel)

        // Gaussian Blur & Canny Edge Detection on the isolated paper mask
        val blurred = Mat()
        Imgproc.GaussianBlur(paperMask, blurred, Size(5.0, 5.0), 0.0)

        val edges = Mat()
        Imgproc.Canny(blurred, edges, 50.0, 150.0)
        Imgproc.dilate(edges, edges, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))

        // Find contours
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

        val totalArea = scaledMat.cols() * scaledMat.rows()
        var bestQuad: Quadrilateral? = null
        var maxArea = 0.0

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            // The notebook must take up at least 15% of the frame
            if (area < totalArea * 0.15) continue

            val contour2f = MatOfPoint2f(*contour.toArray())
            val perimeter = Imgproc.arcLength(contour2f, true)
            val approx = MatOfPoint2f()

            // Polygon approximation: epsilon = 2.5% of perimeter
            Imgproc.approxPolyDP(contour2f, approx, 0.025 * perimeter, true)

            // Look for a 4-corner convex polygon
            if (approx.total() == 4L && area > maxArea && Imgproc.isContourConvex(MatOfPoint(*approx.toArray()))) {
                val points = approx.toArray()
                // Rescale points back to original full-resolution dimensions
                val unscaledPoints = points.map { Point(it.x / scale, it.y / scale) }.toTypedArray()
                bestQuad = orderPoints(unscaledPoints)
                maxArea = area
            }

            contour2f.release()
            approx.release()
        }

        // Release Mats
        originalMat.release()
        scaledMat.release()
        hsvMat.release()
        paperMask.release()
        blurred.release()
        edges.release()
        hierarchy.release()
        contours.forEach { it.release() }

        // Fallback: If no clean quad found, crop with a safe 5% inset
        return bestQuad ?: Quadrilateral(
            topLeft = Point(bitmap.width * 0.05, bitmap.height * 0.05),
            topRight = Point(bitmap.width * 0.95, bitmap.height * 0.05),
            bottomRight = Point(bitmap.width * 0.95, bitmap.height * 0.95),
            bottomLeft = Point(bitmap.width * 0.05, bitmap.height * 0.95)
        )
    }

    /**
     * Orders 4 points consistently: [Top-Left, Top-Right, Bottom-Right, Bottom-Left]
     * Uses sum (x + y) and difference (y - x)
     */
    private fun orderPoints(pts: Array<Point>): Quadrilateral {
        // Top-Left has minimum (x + y), Bottom-Right has maximum (x + y)
        val sumSorted = pts.sortedBy { it.x + it.y }
        val tl = sumSorted.first()
        val br = sumSorted.last()

        // Top-Right has minimum (y - x), Bottom-Left has maximum (y - x)
        val diffSorted = pts.sortedBy { it.y - it.x }
        val tr = diffSorted.first()
        val bl = diffSorted.last()

        return Quadrilateral(topLeft = tl, topRight = tr, bottomRight = br, bottomLeft = bl)
    }

    /**
     * 2. Perspective Transform (Warp Perspective)
     * Straightens angled paper into a flat, top-down rectangular view.
     */
    fun warpPerspective(bitmap: Bitmap, quad: Quadrilateral): Bitmap {
        val srcMat = Mat()
        Utils.bitmapToMat(bitmap, srcMat)

        // Calculate destination width: max distance between top & bottom corners
        val widthTop = hypot(quad.topRight.x - quad.topLeft.x, quad.topRight.y - quad.topLeft.y)
        val widthBottom = hypot(quad.bottomRight.x - quad.bottomLeft.x, quad.bottomRight.y - quad.bottomLeft.y)
        val targetWidth = max(widthTop, widthBottom).toInt().coerceAtLeast(100)

        // Calculate destination height: max distance between left & right corners
        val heightLeft = hypot(quad.bottomLeft.x - quad.topLeft.x, quad.bottomLeft.y - quad.topLeft.y)
        val heightRight = hypot(quad.bottomRight.x - quad.topRight.x, quad.bottomRight.y - quad.topRight.y)
        val targetHeight = max(heightLeft, heightRight).toInt().coerceAtLeast(100)

        val srcPoints = MatOfPoint2f(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)
        val dstPoints = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(targetWidth.toDouble(), 0.0),
            Point(targetWidth.toDouble(), targetHeight.toDouble()),
            Point(0.0, targetHeight.toDouble())
        )

        // Generate 3x3 transformation matrix
        val transformMatrix = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
        val warpedMat = Mat()

        // Perform warp perspective with bicubic interpolation
        Imgproc.warpPerspective(
            srcMat,
            warpedMat,
            transformMatrix,
            Size(targetWidth.toDouble(), targetHeight.toDouble()),
            Imgproc.INTER_CUBIC
        )

        val outputBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(warpedMat, outputBitmap)

        srcMat.release()
        warpedMat.release()
        srcPoints.release()
        dstPoints.release()
        transformMatrix.release()

        return outputBitmap
    }

    /**
     * 3. Image Enhancement & Binarization
     * Adaptive Gaussian Thresholding: whitens paper background and makes text sharp, dark black.
     */
    fun applyAdaptiveBinarization(
        bitmap: Bitmap,
        blockSize: Int = 27,
        subtractedConstantC: Double = 10.0
    ): Bitmap {
        val srcMat = Mat()
        Utils.bitmapToMat(bitmap, srcMat)

        val grayMat = Mat()
        Imgproc.cvtColor(srcMat, grayMat, Imgproc.COLOR_BGR2GRAY)

        val binarizedMat = Mat()
        Imgproc.adaptiveThreshold(
            grayMat,
            binarizedMat,
            255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY,
            blockSize,            // Local neighborhood pixel size (must be odd, e.g. 21, 27, 31)
            subtractedConstantC   // Constant subtracted from mean (removes shadowing)
        )

        val resultBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(binarizedMat, resultBitmap)

        srcMat.release()
        grayMat.release()
        binarizedMat.release()

        return resultBitmap
    }
}
