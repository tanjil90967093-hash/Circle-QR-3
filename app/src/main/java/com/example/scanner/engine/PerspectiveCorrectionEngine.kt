package com.example.scanner.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import com.example.utils.DocumentQuad
import kotlin.math.hypot
import kotlin.math.max

object PerspectiveCorrectionEngine {

    /**
     * Executes perspective transformation mapping the 4 detected document corners
     * to a flat, front-facing rectangular document image.
     */
    fun transformPerspective(bitmap: Bitmap, quad: DocumentQuad): Bitmap {
        val src = floatArrayOf(
            quad.topLeft.x, quad.topLeft.y,
            quad.topRight.x, quad.topRight.y,
            quad.bottomRight.x, quad.bottomRight.y,
            quad.bottomLeft.x, quad.bottomLeft.y
        )

        // Calculate output dimensions
        val topWidth = hypot((quad.topRight.x - quad.topLeft.x).toDouble(), (quad.topRight.y - quad.topLeft.y).toDouble())
        val bottomWidth = hypot((quad.bottomRight.x - quad.bottomLeft.x).toDouble(), (quad.bottomRight.y - quad.bottomLeft.y).toDouble())
        val targetWidth = max(topWidth, bottomWidth).toInt().coerceIn(120, 4200)

        val leftHeight = hypot((quad.bottomLeft.x - quad.topLeft.x).toDouble(), (quad.bottomLeft.y - quad.topLeft.y).toDouble())
        val rightHeight = hypot((quad.bottomRight.x - quad.topRight.x).toDouble(), (quad.bottomRight.y - quad.topRight.y).toDouble())
        val targetHeight = max(leftHeight, rightHeight).toInt().coerceIn(120, 4200)

        val dst = floatArrayOf(
            0f, 0f,
            targetWidth.toFloat(), 0f,
            targetWidth.toFloat(), targetHeight.toFloat(),
            0f, targetHeight.toFloat()
        )

        val matrix = Matrix()
        matrix.setPolyToPoly(src, 0, dst, 0, 4)

        val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(bitmap, matrix, paint)

        return output
    }
}
