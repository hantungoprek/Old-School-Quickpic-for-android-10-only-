package com.example.quickpic

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** Hasil rotasi per item. */
data class RotateResult(
    val uri: Uri,
    val success: Boolean,
    val errorMessage: String? = null,
)

/**
 * Rotasi permanen file gambar/video ke storage fisik sehingga saat dibuka di aplikasi
 * lain (Google Photos, WhatsApp, Gallery bawaan, PC, dll) hasil rotasi tetap permanen.
 *
 * Menjaga tanggal asli file (DATE_ADDED, DATE_TAKEN, EXIF DateTime) agar urutan sorting
 * foto di album tidak bergeser setelah file dirotasi.
 */
suspend fun Context.rotateMediaFilePermanently(
    uri: Uri,
    degrees: Int,
    thumbnailCache: ThumbnailCache? = null,
): RotateResult = withContext(Dispatchers.IO) {
    if (degrees == 0) return@withContext RotateResult(uri, true)

    val mimeType = contentResolver.getType(uri) ?: "image/jpeg"

    return@withContext when {
        mimeType.startsWith("image/") ->
            rotateImageFilePhysically(uri, degrees, mimeType, thumbnailCache)
        mimeType.startsWith("video/") ->
            rotateVideoMetadata(uri, degrees, thumbnailCache)
        else ->
            RotateResult(uri, false, "Format tidak didukung: $mimeType")
    }
}

// ---------------------------------------------------------------------------
// Rotasi Gambar Fisik (Pixel Data)
// ---------------------------------------------------------------------------

private fun Context.rotateImageFilePhysically(
    uri: Uri,
    requestedDegrees: Int,
    mimeType: String,
    thumbnailCache: ThumbnailCache?,
): RotateResult {
    val tempFile = File(cacheDir, "rotate_temp_${System.currentTimeMillis()}.${if (mimeType.contains("png")) "png" else "jpg"}")
    try {
        // 1. Ambil metadata tanggal asli dari MediaStore agar urutan foto tidak meloncat
        var origDateAdded = 0L
        var origDateTaken = 0L
        var origDateModified = 0L

        try {
            val projection = arrayOf(
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.DATE_MODIFIED,
                "datetaken",
            )
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val addedIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                    val modIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                    val takenIdx = cursor.getColumnIndex("datetaken")
                    if (addedIdx >= 0) origDateAdded = cursor.getLong(addedIdx)
                    if (modIdx >= 0) origDateModified = cursor.getLong(modIdx)
                    if (takenIdx >= 0) origDateTaken = cursor.getLong(takenIdx)
                }
            }
        } catch (_: Exception) {}

        // 2. Baca byte gambar asli
        val rawBytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return RotateResult(uri, false, "Tidak dapat membaca file gambar.")

        // 3. Baca orientasi EXIF awal dan preservasi semua tag metadata berharga
        var existingExifDegrees = 0
        val exifAttributes = mutableMapOf<String, String>()
        val exifTagsToPreserve = listOf(
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_WHITE_BALANCE,
        )

        try {
            rawBytes.inputStream().use { stream ->
                val exif = ExifInterface(stream)
                val orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
                existingExifDegrees = exifOrientationToDegrees(orientation)

                for (tag in exifTagsToPreserve) {
                    val value = exif.getAttribute(tag)
                    if (!value.isNullOrBlank()) {
                        exifAttributes[tag] = value
                    }
                }
            }
        } catch (_: Exception) {}

        // 4. Decode Bitmap dengan resolusi penuh
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, boundsOpts)

        var sampleSize = 1
        val maxDimension = 8192
        while (boundsOpts.outWidth / sampleSize > maxDimension || boundsOpts.outHeight / sampleSize > maxDimension) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val sourceBitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts)
            ?: return RotateResult(uri, false, "Gagal men-decode bitmap gambar.")

        // 5. Hitung total rotasi fisik yang diperlukan
        val totalRotationDegrees = ((existingExifDegrees + requestedDegrees) % 360 + 360) % 360

        val matrix = Matrix().apply {
            postRotate(totalRotationDegrees.toFloat())
        }

        val rotatedBitmap = Bitmap.createBitmap(
            sourceBitmap,
            0,
            0,
            sourceBitmap.width,
            sourceBitmap.height,
            matrix,
            true,
        )
        if (rotatedBitmap !== sourceBitmap) {
            sourceBitmap.recycle()
        }

        // 6. Tulis ke file temporer lokal
        val isPng = mimeType.contains("png", ignoreCase = true)
        val isWebp = mimeType.contains("webp", ignoreCase = true)
        val format = when {
            isPng -> Bitmap.CompressFormat.PNG
            isWebp -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSLESS else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
            else -> Bitmap.CompressFormat.JPEG
        }
        val quality = if (isPng) 100 else 96

        FileOutputStream(tempFile).use { out ->
            rotatedBitmap.compress(format, quality, out)
            out.flush()
        }
        rotatedBitmap.recycle()

        // 7. Tulis kembali metadata EXIF ke file temporer
        if (!isPng) {
            try {
                val tempExif = ExifInterface(tempFile.absolutePath)
                for ((tag, value) in exifAttributes) {
                    tempExif.setAttribute(tag, value)
                }
                // Reset orientasi ke NORMAL karena pixelnya sudah diputar secara fisik
                tempExif.setAttribute(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL.toString(),
                )
                tempExif.saveAttributes()
            } catch (_: Exception) {}
        }

        // 8. Salin isi tempFile kembali ke MediaStore URI
        val newBytes = tempFile.readBytes()
        val outStream = contentResolver.openOutputStream(uri, "rwt")
            ?: contentResolver.openOutputStream(uri, "wt")
            ?: contentResolver.openOutputStream(uri, "w")
            ?: return RotateResult(uri, false, "Tidak dapat membuka file penyimpanan untuk menulis. Pastikan izin telah diberikan.")

        outStream.use { out ->
            out.write(newBytes)
            out.flush()
        }

        // 9. Update database MediaStore (Pertahankan tanggal asli agar urutan foto tidak rusak)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.ORIENTATION, 0)
            put(MediaStore.MediaColumns.SIZE, newBytes.size.toLong())
            if (origDateAdded > 0L) {
                put(MediaStore.MediaColumns.DATE_ADDED, origDateAdded)
            }
            if (origDateTaken > 0L) {
                put("datetaken", origDateTaken)
            }
            if (origDateModified > 0L) {
                put(MediaStore.MediaColumns.DATE_MODIFIED, origDateModified)
            }
        }
        try {
            contentResolver.update(uri, values, null, null)
        } catch (_: Exception) {}

        try {
            contentResolver.notifyChange(uri, null)
        } catch (_: Exception) {}

        // 10. Invalidate semua layer cache (QuickPic Thumbnail Cache & Coil ImageLoader)
        thumbnailCache?.invalidate(uri)
        try {
            imageLoader.memoryCache?.clear()
            imageLoader.diskCache?.clear()
        } catch (_: Exception) {}

        return RotateResult(uri, true)
    } catch (e: Throwable) {
        return RotateResult(uri, false, "${e.javaClass.simpleName}: ${e.message ?: "Error tidak diketahui"}")
    } finally {
        if (tempFile.exists()) {
            tempFile.delete()
        }
    }
}

// ---------------------------------------------------------------------------
// Rotasi Video Metadata
// ---------------------------------------------------------------------------

private fun Context.rotateVideoMetadata(
    uri: Uri,
    degrees: Int,
    thumbnailCache: ThumbnailCache?,
): RotateResult {
    return runCatching {
        val projection = arrayOf(
            MediaStore.Video.Media.ORIENTATION,
            MediaStore.MediaColumns.DATE_ADDED,
            "datetaken",
        )
        var currentRotation = 0
        var origDateAdded = 0L
        var origDateTaken = 0L

        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val rotIdx = cursor.getColumnIndex(MediaStore.Video.Media.ORIENTATION)
                val addedIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                val takenIdx = cursor.getColumnIndex("datetaken")
                if (rotIdx >= 0) currentRotation = cursor.getInt(rotIdx)
                if (addedIdx >= 0) origDateAdded = cursor.getLong(addedIdx)
                if (takenIdx >= 0) origDateTaken = cursor.getLong(takenIdx)
            }
        }

        val newRotation = ((currentRotation + degrees) % 360 + 360) % 360

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.ORIENTATION, newRotation)
            if (origDateAdded > 0L) put(MediaStore.MediaColumns.DATE_ADDED, origDateAdded)
            if (origDateTaken > 0L) put("datetaken", origDateTaken)
        }
        val updated = contentResolver.update(uri, values, null, null)
        try {
            contentResolver.notifyChange(uri, null)
        } catch (_: Exception) {}

        thumbnailCache?.invalidate(uri)
        try {
            imageLoader.memoryCache?.clear()
            imageLoader.diskCache?.clear()
        } catch (_: Exception) {}

        if (updated > 0) {
            RotateResult(uri, true)
        } else {
            RotateResult(uri, false, "Metadata orientasi video tidak dapat diupdate.")
        }
    }.getOrElse { e ->
        RotateResult(uri, false, "${e.javaClass.simpleName}: ${e.message ?: "Error tidak diketahui"}")
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun exifOrientationToDegrees(orientation: Int): Int = when (orientation) {
    ExifInterface.ORIENTATION_ROTATE_90 -> 90
    ExifInterface.ORIENTATION_ROTATE_180 -> 180
    ExifInterface.ORIENTATION_ROTATE_270 -> 270
    else -> 0
}
