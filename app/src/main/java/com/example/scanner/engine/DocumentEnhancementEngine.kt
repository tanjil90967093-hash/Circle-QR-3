package com.example.scanner.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.min

enum class EnhancementMode(val label: String) {
    ORIGINAL("Original Color"),
    DOCUMENT_COLOR("Document Color"),
    CAMSCANNER_MAGIC("CamScanner Magic"),
    GRAYSCALE("Grayscale"),
    BLACK_WHITE("Black & White"),
    SHADOW_REDUCTION("Shadow Reduction"),
    SHARPENING("Sharpening")
}

object DocumentEnhancementEngine {

    fun enhance(bitmap: Bitmap, mode: EnhancementMode): Bitmap {
        return when (mode) {
            EnhancementMode.ORIGINAL -> applyOriginal(bitmap)
            EnhancementMode.DOCUMENT_COLOR -> applyDocumentColor(bitmap)
            EnhancementMode.CAMSCANNER_MAGIC -> applyCamScannerMagic(bitmap)
            EnhancementMode.GRAYSCALE -> applyGrayscale(bitmap)
            EnhancementMode.BLACK_WHITE -> applyAdaptiveThreshold(bitmap)
            EnhancementMode.SHADOW_REDUCTION -> applyShadowReduction(bitmap)
            EnhancementMode.SHARPENING -> applySharpening(bitmap)
        }
    }

    /**
     * Mode A: Original Color - Modest contrast & natural tones.
     */
    private fun applyOriginal(bitmap: Bitmap): Bitmap {
        val cm = ColorMatrix(floatArrayOf(
            1.04f, 0f, 0f, 0f, 2f,
            0f, 1.04f, 0f, 0f, 2f,
            0f, 0f, 1.04f, 0f, 2f,
            0f, 0f, 0f, 1f, 0f
        ))
        return applyColorMatrix(bitmap, cm)
    }

    /**
     * Mode B: Document Color (Magic Color) - Boosts readability, whitens background, preserves colored inks.
     */
    private fun applyDocumentColor(bitmap: Bitmap): Bitmap {
        val satMatrix = ColorMatrix().apply { setSaturation(1.30f) }
        val contrastMatrix = ColorMatrix(floatArrayOf(
            1.32f, 0f, 0f, 0f, -14f,
            0f, 1.32f, 0f, 0f, -14f,
            0f, 0f, 1.32f, 0f, -14f,
            0f, 0f, 0f, 1f, 0f
        ))
        val combined = ColorMatrix().apply { setConcat(contrastMatrix, satMatrix) }
        return applyColorMatrix(bitmap, combined)
    }

    /**
     * Mode C: Grayscale - Perceptual luminance contrast without clipping fine text strokes.
     */
    private fun applyGrayscale(bitmap: Bitmap): Bitmap {
        val satMatrix = ColorMatrix().apply { setSaturation(0f) }
        val contrast = ColorMatrix(floatArrayOf(
            1.20f, 0f, 0f, 0f, -10f,
            0f, 1.20f, 0f, 0f, -10f,
            0f, 0f, 1.20f, 0f, -10f,
            0f, 0f, 0f, 1f, 0f
        ))
        val combined = ColorMatrix().apply { setConcat(contrast, satMatrix) }
        return applyColorMatrix(bitmap, combined)
    }

    /**
     * Mode: CamScanner Magic Filter - High dynamic contrast that whitens paper background
     * and intensifies ink strokes while preserving colored pen inks.
     */
    fun applyCamScannerMagic(bitmap: Bitmap): Bitmap {
        val satMatrix = ColorMatrix().apply { setSaturation(1.22f) }
        val contrast = 1.68f
        val brightness = 26f
        val contrastMatrix = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, brightness - 128f * (contrast - 1f),
            0f, contrast, 0f, 0f, brightness - 128f * (contrast - 1f),
            0f, 0f, contrast, 0f, brightness - 128f * (contrast - 1f),
            0f, 0f, 0f, 1f, 0f
        ))
        val combined = ColorMatrix().apply { setConcat(contrastMatrix, satMatrix) }
        val boosted = applyColorMatrix(bitmap, combined)
        return applySharpening(boosted)
    }

    /**
     * Mode: Adaptive Thresholding (CamScanner Clean Black & White).
     * Computes local neighborhood means using an integral image O(1) algorithm,
     * stripping bedsheet shadows and making text ultra-sharp black and paper pure white.
     */
    fun applyAdaptiveThreshold(source: Bitmap, windowSize: Int = 29, offset: Int = 9): Bitmap {
        val w = source.width
        val h = source.height
        if (w < 8 || h < 8) return applyGrayscale(source)

        return try {
            val pixels = IntArray(w * h)
            source.getPixels(pixels, 0, w, 0, 0, w, h)

            val gray = IntArray(w * h)
            for (i in pixels.indices) {
                val c = pixels[i]
                gray[i] = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
            }

            val integral = LongArray((w + 1) * (h + 1))
            for (y in 0 until h) {
                var rowSum = 0L
                val rowOffset = (y + 1) * (w + 1)
                val prevRowOffset = y * (w + 1)
                val grayRow = y * w
                for (x in 0 until w) {
                    rowSum += gray[grayRow + x]
                    integral[rowOffset + (x + 1)] = integral[prevRowOffset + (x + 1)] + rowSum
                }
            }

            val half = windowSize / 2
            val outPixels = IntArray(w * h)

            for (y in 0 until h) {
                val y1 = max(0, y - half)
                val y2 = min(h - 1, y + half)
                val intY1 = y1 * (w + 1)
                val intY2 = (y2 + 1) * (w + 1)
                val row = y * w

                for (x in 0 until w) {
                    val x1 = max(0, x - half)
                    val x2 = min(w - 1, x + half)

                    val count = (x2 - x1 + 1) * (y2 - y1 + 1)
                    val sum = integral[intY2 + (x2 + 1)] -
                            integral[intY1 + (x2 + 1)] -
                            integral[intY2 + x1] +
                            integral[intY1 + x1]

                    val mean = (sum / count).toInt()
                    val pixel = gray[row + x]
                    outPixels[row + x] = if (pixel < (mean - offset)) Color.BLACK else Color.WHITE
                }
            }

            val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            result.setPixels(outPixels, 0, w, 0, 0, w, h)
            result
        } catch (_: Exception) {
            applyGrayscale(source)
        }
    }

    /**
     * Mode E: Shadow Reduction - Lifts shadows and normalizes uneven background illumination.
     */
    private fun applyShadowReduction(bitmap: Bitmap): Bitmap {
        val satMatrix = ColorMatrix().apply { setSaturation(1.15f) }
        val shadowLift = ColorMatrix(floatArrayOf(
            1.28f, 0f, 0f, 0f, 24f,
            0f, 1.28f, 0f, 0f, 24f,
            0f, 0f, 1.28f, 0f, 24f,
            0f, 0f, 0f, 1f, 0f
        ))
        val combined = ColorMatrix().apply { setConcat(shadowLift, satMatrix) }
        return applyColorMatrix(bitmap, combined)
    }

    /**
     * Mode F: Sharpening - Conservative 3x3 unsharp convolution to crisp up text strokes.
     */
    private fun applySharpening(bitmap: Bitmap): Bitmap {
        // Base contrast & saturation
        val base = applyDocumentColor(bitmap)
        val w = base.width
        val h = base.height
        if (w < 16 || h < 16) return base

        try {
            val srcPixels = IntArray(w * h)
            base.getPixels(srcPixels, 0, w, 0, 0, w, h)
            val outPixels = IntArray(w * h)

            // Copy border rows
            System.arraycopy(srcPixels, 0, outPixels, 0, w)
            System.arraycopy(srcPixels, (h - 1) * w, outPixels, (h - 1) * w, w)

            // 3x3 sharpening kernel: [0, -0.4, 0; -0.4, 2.6, -0.4; 0, -0.4, 0]
            val centerCoeff = 2.4f
            val crossCoeff = -0.35f

            for (y in 1 until h - 1) {
                val row = y * w
                outPixels[row] = srcPixels[row]
                outPixels[row + w - 1] = srcPixels[row + w - 1]

                for (x in 1 until w - 1) {
                    val idx = row + x
                    val pC = srcPixels[idx]
                    val pT = srcPixels[idx - w]
                    val pB = srcPixels[idx + w]
                    val pL = srcPixels[idx - 1]
                    val pR = srcPixels[idx + 1]

                    val rC = (pC shr 16) and 0xFF
                    val gC = (pC shr 8) and 0xFF
                    val bC = pC and 0xFF

                    val rCross = (((pT shr 16) and 0xFF) + ((pB shr 16) and 0xFF) + ((pL shr 16) and 0xFF) + ((pR shr 16) and 0xFF))
                    val gCross = (((pT shr 8) and 0xFF) + ((pB shr 8) and 0xFF) + ((pL shr 8) and 0xFF) + ((pR shr 8) and 0xFF))
                    val bCross = ((pT and 0xFF) + (pB and 0xFF) + (pL and 0xFF) + (pR and 0xFF))

                    val nr = (rC * centerCoeff + rCross * crossCoeff).toInt().coerceIn(0, 255)
                    val ng = (gC * centerCoeff + gCross * crossCoeff).toInt().coerceIn(0, 255)
                    val nb = (bC * centerCoeff + bCross * crossCoeff).toInt().coerceIn(0, 255)

                    outPixels[idx] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
                }
            }

            return Bitmap.createBitmap(outPixels, w, h, Bitmap.Config.ARGB_8888)
        } catch (_: Exception) {
            return base
        }
    }

    private fun applyColorMatrix(bitmap: Bitmap, cm: ColorMatrix): Bitmap {
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(cm)
        }
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return result
    }
}
