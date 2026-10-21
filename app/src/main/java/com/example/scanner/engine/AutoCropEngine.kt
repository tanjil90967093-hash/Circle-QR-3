package com.example.scanner.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import com.example.utils.DocumentQuad

data class ProcessedScanResult(
    val rawOriginal: Bitmap,
    val croppedBitmap: Bitmap,
    val enhancedBitmap: Bitmap,
    val quad: DocumentQuad,
    val rotationAngle: Float,
    val enhancementMode: EnhancementMode
)

object AutoCropEngine {

    private val detector: IDocumentDetector = ContourDocumentDetector()

    /**
     * Complete automatic scanning pipeline:
     * 1. Detects document boundary (cuts away bed sheet / table / outside area)
     * 2. Validates detected quadrilateral (convexity, area, aspect ratio)
     * 3. Transforms perspective into a front-facing flat document
     * 4. Automatically detects orientation & deskews (rotates sideways photo upright)
     * 5. Enhances readability with high-clarity document enhancement
     */
    suspend fun processCapture(
        context: Context,
        rawBitmap: Bitmap,
        preferredMode: EnhancementMode = EnhancementMode.DOCUMENT_COLOR,
        manualQuad: DocumentQuad? = null
    ): ProcessedScanResult {
        // Step 1: Detect corners
        val quad = manualQuad ?: detector.detectDocument(rawBitmap) ?: DocumentQuad(
            topLeft = PointF(rawBitmap.width * 0.05f, rawBitmap.height * 0.05f),
            topRight = PointF(rawBitmap.width * 0.95f, rawBitmap.height * 0.05f),
            bottomRight = PointF(rawBitmap.width * 0.95f, rawBitmap.height * 0.95f),
            bottomLeft = PointF(rawBitmap.width * 0.05f, rawBitmap.height * 0.95f),
            isAutoDetected = false,
            confidence = 0.5f
        )

        // Step 2 & 3: Perspective rectification
        val rectified = PerspectiveCorrectionEngine.transformPerspective(rawBitmap, quad)

        // Step 4: Auto-orientation & deskew (makes sideways document upright)
        val (upright, rotation) = ImageOrientationCorrector.autoCorrectOrientation(rectified)

        // Step 5: Readability & clarity enhancement
        val enhanced = DocumentEnhancementEngine.enhance(upright, preferredMode)

        return ProcessedScanResult(
            rawOriginal = rawBitmap,
            croppedBitmap = upright,
            enhancedBitmap = enhanced,
            quad = quad,
            rotationAngle = rotation,
            enhancementMode = preferredMode
        )
    }
}
