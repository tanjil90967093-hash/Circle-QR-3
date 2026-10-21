package com.example.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.data.local.entity.HistoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object ScanThumbnailHelper {

    /**
     * Resolves and loads a thumbnail bitmap for any scan history item.
     * - For QR/Barcode scans: generates real QR code bitmap preview
     * - For Documents/Photos: loads image or extracts page 1 of PDF
     */
    suspend fun loadThumbnail(context: Context, item: HistoryEntity): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val id = item.toolId.lowercase()
            val details = item.details.trim()
            val title = item.title.lowercase()

            // 1. QR or Barcode: generate real QR preview bitmap
            if (id.contains("qr") || id.contains("barcode") || title.contains("qr") || title.contains("barcode")) {
                if (details.isNotBlank() && !details.endsWith(".jpg") && !details.endsWith(".png") && !details.endsWith(".pdf")) {
                    return@withContext QrCodeHelper.generateQrBitmap(details, 140, 140)
                }
            }

            // 2. Direct file resolution
            val resolvedFile = resolveFile(context, item)
            if (resolvedFile != null && resolvedFile.exists()) {
                if (resolvedFile.name.endsWith(".pdf", ignoreCase = true)) {
                    val uri = Uri.fromFile(resolvedFile)
                    val bmps = PdfUtils.pdfToBitmaps(context, uri, 1)
                    if (bmps.isNotEmpty()) return@withContext bmps.first()
                } else {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                    val bmp = BitmapFactory.decodeFile(resolvedFile.absolutePath, opts)
                    if (bmp != null) return@withContext bmp
                }
            }

            // 3. Fallback: Check newest file in scanned_images
            val scannedDir = File(context.filesDir, "scanned_images")
            if (scannedDir.exists() && (id.contains("doc") || id.contains("image") || id.contains("scan") || title.contains("document") || title.contains("photo"))) {
                val files = scannedDir.listFiles()?.filter { it.isFile && (it.name.endsWith(".jpg") || it.name.endsWith(".png")) }
                val latest = files?.maxByOrNull { it.lastModified() }
                if (latest != null) {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    return@withContext BitmapFactory.decodeFile(latest.absolutePath, opts)
                }
            }
        } catch (_: Exception) {}
        null
    }

    /**
     * Resolves the actual File on disk corresponding to a scan item.
     */
    fun resolveFile(context: Context, item: HistoryEntity): File? {
        val details = item.details.trim()
        val cleanName = details.substringBefore(" (").trim()

        val candidateDirs = listOf(
            File(context.filesDir, "scanned_images"),
            File(context.filesDir, "pdfs"),
            File(context.filesDir, "images"),
            File(context.filesDir, "exported_media"),
            context.cacheDir
        )

        for (dir in candidateDirs) {
            val f = File(dir, cleanName)
            if (f.exists() && f.isFile) return f
        }

        val direct = File(cleanName)
        if (direct.exists() && direct.isFile) return direct

        // Also check if any file inside scanned_images or pdfs matches
        for (dir in listOf(File(context.filesDir, "scanned_images"), File(context.filesDir, "pdfs"))) {
            if (dir.exists()) {
                val matches = dir.listFiles()?.filter { it.isFile && it.name.contains(cleanName, ignoreCase = true) }
                if (!matches.isNullOrEmpty()) return matches.first()
            }
        }

        return null
    }
}
