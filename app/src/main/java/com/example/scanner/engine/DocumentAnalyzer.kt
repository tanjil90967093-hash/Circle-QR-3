package com.example.scanner.engine

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max

object DocumentAnalyzer {

    private const val TAG = "DocumentAnalyzer"

    sealed class ScanResult {
        data class Success(val croppedBitmap: Bitmap, val corners: List<Point>) : ScanResult()
        data class Fallback(val originalFixedBitmap: Bitmap, val reason: String) : ScanResult()
    }

    /**
     * মূল প্রসেসিং পাইপলাইন
     */
    fun processImage(bitmap: Bitmap): ScanResult {
        val originalMat = Mat()
        Utils.bitmapToMat(bitmap, originalMat)

        // 1. Downscale for faster processing & better edge connection
        val ratio = originalMat.rows().toDouble() / 600.0
        val downscaledHeight = 600.0
        val downscaledWidth = originalMat.cols().toDouble() / ratio
        val resizedMat = Mat()
        Imgproc.resize(originalMat, resizedMat, Size(downscaledWidth, downscaledHeight))

        // 2. Preprocessing (Grayscale, Bilateral Filter, Canny)
        val gray = Mat()
        Imgproc.cvtColor(resizedMat, gray, Imgproc.COLOR_BGR2GRAY)

        // Bilateral filter preserves sharp edges (paper boundary) while removing texture (bedsheet/wood)
        val filtered = Mat()
        Imgproc.bilateralFilter(gray, filtered, 9, 75.0, 75.0)

        // Adaptive Canny to handle low light & shadows
        val edges = Mat()
        Imgproc.Canny(filtered, edges, 40.0, 120.0)

        // 3. Morphological Operation (Close broken edges)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        Imgproc.dilate(edges, edges, kernel)

        // 4. Find Contours
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        // 5. Sort contours by Area (Largest first)
        contours.sortByDescending { Imgproc.contourArea(it) }

        var documentCorners: MatOfPoint2f? = null

        // 6. Boundary Validation Logic
        for (contour in contours) {
            val area = Imgproc.contourArea(contour)

            // Validation 1: Area must be at least 15% of the image
            if (area < (resizedMat.rows() * resizedMat.cols() * 0.15)) continue

            val contour2f = MatOfPoint2f(*contour.toArray())
            val perimeter = Imgproc.arcLength(contour2f, true)
            val approx = MatOfPoint2f()

            // Approx poly to smooth out the edges
            Imgproc.approxPolyDP(contour2f, approx, 0.03 * perimeter, true)

            // Validation 2 & 3: Must have exactly 4 corners AND be Convex (no inward dents)
            if (approx.toArray().size == 4 && Imgproc.isContourConvex(MatOfPoint(*approx.toArray()))) {
                documentCorners = approx
                break
            }
        }

        // Release temporary Mats
        resizedMat.release()
        gray.release()
        filtered.release()
        edges.release()
        kernel.release()
        hierarchy.release()

        // 7. Action based on Detection
        return if (documentCorners != null) {
            // Scale corners back to original image resolution
            val scaledCorners = documentCorners.toArray().map { Point(it.x * ratio, it.y * ratio) }
            val sortedCorners = orderPoints(scaledCorners)

            // Perform Perspective Transformation (Auto Crop)
            val croppedMat = performPerspectiveWarp(originalMat, sortedCorners)

            val finalBitmap = Bitmap.createBitmap(croppedMat.cols(), croppedMat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(croppedMat, finalBitmap)

            originalMat.release()
            croppedMat.release()

            ScanResult.Success(finalBitmap, sortedCorners)
        } else {
            // FALLBACK: Could not confidently find document. Return original for Manual Crop.
            val fallbackBitmap = Bitmap.createBitmap(originalMat.cols(), originalMat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(originalMat, fallbackBitmap)
            originalMat.release()

            ScanResult.Fallback(fallbackBitmap, "Confidence too low or no valid 4-point paper detected.")
        }
    }

    /**
     * 8. Sort corners: Top-Left, Top-Right, Bottom-Right, Bottom-Left
     */
    fun orderPoints(points: List<Point>): List<Point> {
        val sortedByY = points.sortedBy { it.y }
        val top = sortedByY.take(2).sortedBy { it.x }
        val bottom = sortedByY.drop(2).sortedBy { it.x } // Sort bottom by X as well

        return listOf(top[0], top[1], bottom[1], bottom[0])
    }

    /**
     * 9. Perspective Correction & Crop
     */
    fun performPerspectiveWarp(src: Mat, corners: List<Point>): Mat {
        val tl = corners[0]
        val tr = corners[1]
        val br = corners[2]
        val bl = corners[3]

        // Calculate max width and height for the new matrix
        val widthA = hypot(br.x - bl.x, br.y - bl.y)
        val widthB = hypot(tr.x - tl.x, tr.y - tl.y)
        val maxWidth = max(widthA, widthB).toInt().coerceAtLeast(64)

        val heightA = hypot(tr.x - br.x, tr.y - br.y)
        val heightB = hypot(tl.x - bl.x, tl.y - bl.y)
        val maxHeight = max(heightA, heightB).toInt().coerceAtLeast(64)

        val srcPoints = MatOfPoint2f(tl, tr, br, bl)
        val dstPoints = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(maxWidth.toDouble() - 1, 0.0),
            Point(maxWidth.toDouble() - 1, maxHeight.toDouble() - 1),
            Point(0.0, maxHeight.toDouble() - 1)
        )

        val transformMatrix = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
        val destMat = Mat()
        Imgproc.warpPerspective(src, destMat, transformMatrix, Size(maxWidth.toDouble(), maxHeight.toDouble()))

        srcPoints.release()
        dstPoints.release()
        transformMatrix.release()
        return destMat
    }
}
