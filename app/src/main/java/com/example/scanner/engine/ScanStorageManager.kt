package com.example.scanner.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.UtilityHubApp
import com.example.data.local.entity.HistoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class SavedScanMeta(
    val scanId: String,
    val title: String,
    val processedFile: File,
    val rawFile: File?,
    val croppedFile: File?,
    val thumbnailFile: File,
    val timestamp: Long,
    val pageCount: Int = 1
)

object ScanStorageManager {

    suspend fun saveScan(
        context: Context,
        title: String,
        result: ProcessedScanResult,
        pageCount: Int = 1
    ): SavedScanMeta = withContext(Dispatchers.IO) {
        val scanId = UUID.randomUUID().toString().take(8)
        val timestamp = System.currentTimeMillis()
        val safeTitle = title.trim().ifBlank { "Scan_$scanId" }

        val scannedDir = File(context.filesDir, "scanned_images").apply { mkdirs() }
        val rawDir = File(context.filesDir, "raw_scans").apply { mkdirs() }
        val croppedDir = File(context.filesDir, "cropped_scans").apply { mkdirs() }
        val thumbDir = File(context.filesDir, "thumbnails").apply { mkdirs() }

        // 1. Save Enhanced Final Image
        val processedFile = File(scannedDir, "doc_${scanId}.jpg")
        FileOutputStream(processedFile).use { out ->
            result.enhancedBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }

        // 2. Save Raw Original Image
        val rawFile = File(rawDir, "raw_${scanId}.jpg")
        FileOutputStream(rawFile).use { out ->
            result.rawOriginal.compress(Bitmap.CompressFormat.JPEG, 88, out)
        }

        // 3. Save Cropped Perspective Image
        val croppedFile = File(croppedDir, "cropped_${scanId}.jpg")
        FileOutputStream(croppedFile).use { out ->
            result.croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }

        // 4. Save Downscaled Thumbnail for fast history rendering
        val thumbFile = File(thumbDir, "thumb_${scanId}.jpg")
        val thumbW = 160
        val thumbH = (result.enhancedBitmap.height * (thumbW.toFloat() / result.enhancedBitmap.width)).toInt().coerceIn(80, 240)
        val thumbBmp = Bitmap.createScaledBitmap(result.enhancedBitmap, thumbW, thumbH, true)
        FileOutputStream(thumbFile).use { out ->
            thumbBmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        }
        if (thumbBmp != result.enhancedBitmap) thumbBmp.recycle()

        // 5. Save to Room database history
        UtilityHubApp.instance.repository.addHistory(
            toolId = "doc_scanner",
            title = safeTitle,
            details = processedFile.name
        )

        SavedScanMeta(
            scanId = scanId,
            title = safeTitle,
            processedFile = processedFile,
            rawFile = rawFile,
            croppedFile = croppedFile,
            thumbnailFile = thumbFile,
            timestamp = timestamp,
            pageCount = pageCount
        )
    }

    suspend fun deleteScan(context: Context, historyId: Long, fileName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // Delete record from Room database
            UtilityHubApp.instance.repository.deleteHistory(historyId)

            val scanId = fileName.removePrefix("doc_").removeSuffix(".jpg")

            // Remove all associated files
            val filesToDelete = listOf(
                File(context.filesDir, "scanned_images/$fileName"),
                File(context.filesDir, "raw_scans/raw_$scanId.jpg"),
                File(context.filesDir, "cropped_scans/cropped_$scanId.jpg"),
                File(context.filesDir, "thumbnails/thumb_$scanId.jpg"),
                File(context.filesDir, "pdfs/$fileName")
            )

            for (f in filesToDelete) {
                if (f.exists()) f.delete()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun loadRawOriginal(context: Context, fileName: String): Bitmap? {
        val scanId = fileName.removePrefix("doc_").removeSuffix(".jpg")
        val rawFile = File(context.filesDir, "raw_scans/raw_$scanId.jpg")
        return if (rawFile.exists()) {
            BitmapFactory.decodeFile(rawFile.absolutePath)
        } else null
    }
}
