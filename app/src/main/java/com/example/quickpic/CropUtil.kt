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

/**
 * Rect crop ternormalisasi (0f..1f) relatif terhadap bitmap SETELAH rotasi EXIF awal +
 * rotasi preview dari editor diterapkan (yaitu, relatif terhadap apa yang benar-benar
 * DILIHAT user di layar, bukan raw pixel data sebelum koreksi orientasi).
 */
data class NormalizedCropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** Jaga rect tetap valid: setiap koordinat di 0f..1f dan right>left, bottom>top. */
    fun coerceValid(): NormalizedCropRect {
        val l = left.coerceIn(0f, 1f)
        val t = top.coerceIn(0f, 1f)
        val r = right.coerceIn(l, 1f)
        val b = bottom.coerceIn(t, 1f)
        return NormalizedCropRect(l, t, r, b)
    }

    companion object {
        val FULL = NormalizedCropRect(0f, 0f, 1f, 1f)
    }
}

/** Hasil operasi crop. */
data class CropResult(
    val uri: Uri,
    val success: Boolean,
    val errorMessage: String? = null,
)

/**
 * Crop (+ rotasi tambahan opsional dari editor) permanen file gambar ke storage fisik,
 * menimpa URI yang sama persis. Mengikuti pola yang SAMA dengan
 * rotateMediaFilePermanently (MediaRotationUtil.kt): baca bytes asli, preservasi tag EXIF
 * berharga, decode dengan cap ukuran aman, transform sekali, tulis ke temp file, salin
 * balik ke URI asli, update ContentValues (termasuk WIDTH/HEIGHT baru karena dimensi
 * berubah), invalidate cache. Hanya SATU kali kompresi JPEG dilakukan (tidak berulang).
 */
suspend fun Context.cropMediaFilePermanently(
    uri: Uri,
    cropRect: NormalizedCropRect,
    extraRotationDegrees: Int = 0,
    thumbnailCache: ThumbnailCache? = null,
): CropResult = withContext(Dispatchers.IO) {
    val mimeType = contentResolver.getType(uri) ?: "image/jpeg"
    if (!mimeType.startsWith("image/")) {
        return@withContext CropResult(uri, false, "Crop hanya didukung untuk foto.")
    }

    val tempFile = File(cacheDir, "crop_temp_${System.currentTimeMillis()}.${if (mimeType.contains("png")) "png" else "jpg"}")
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
            ?: return@withContext CropResult(uri, false, "Tidak dapat membaca file gambar.")

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
                val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                existingExifDegrees = exifOrientationDegrees(orientation)
                for (tag in exifTagsToPreserve) {
                    val value = exif.getAttribute(tag)
                    if (!value.isNullOrBlank()) exifAttributes[tag] = value
                }
            }
        } catch (_: Exception) {}

        // 4. Decode Bitmap dengan resolusi penuh (dengan cap ukuran aman, sama seperti rotate)
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
            ?: return@withContext CropResult(uri, false, "Gagal men-decode bitmap gambar.")

        // 5. Terapkan rotasi EXIF awal + rotasi tambahan dari editor (kalau ada) sekaligus,
        //    supaya bitmap berada persis dalam orientasi yang SAMA seperti yang dilihat user
        //    saat memilih area crop di CropScreen.
        val totalRotationDegrees = ((existingExifDegrees + extraRotationDegrees) % 360 + 360) % 360
        val rotatedBitmap = if (totalRotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(totalRotationDegrees.toFloat()) }
            Bitmap.createBitmap(sourceBitmap, 0, 0, sourceBitmap.width, sourceBitmap.height, matrix, true).also {
                if (it !== sourceBitmap) sourceBitmap.recycle()
            }
        } else {
            sourceBitmap
        }

        // 6. Hitung rect piksel absolut dari rect ternormalisasi terhadap bitmap yang SUDAH
        //    diputar di atas — inilah ruang koordinat yang sama persis dengan yang dipakai
        //    CropScreen saat menghitung NormalizedCropRect dari gesture layar.
        val bw = rotatedBitmap.width
        val bh = rotatedBitmap.height
        val pixelLeft = (cropRect.left * bw).toInt().coerceIn(0, bw - 1)
        val pixelTop = (cropRect.top * bh).toInt().coerceIn(0, bh - 1)
        val pixelRight = (cropRect.right * bw).toInt().coerceIn(pixelLeft + 1, bw)
        val pixelBottom = (cropRect.bottom * bh).toInt().coerceIn(pixelTop + 1, bh)
        val cropWidth = pixelRight - pixelLeft
        val cropHeight = pixelBottom - pixelTop

        val croppedBitmap = Bitmap.createBitmap(rotatedBitmap, pixelLeft, pixelTop, cropWidth, cropHeight)
        if (croppedBitmap !== rotatedBitmap) rotatedBitmap.recycle()

        // 7. Tulis ke file temporer lokal — SATU kali kompresi saja, tidak berulang
        val isPng = mimeType.contains("png", ignoreCase = true)
        val isWebp = mimeType.contains("webp", ignoreCase = true)
        val format = when {
            isPng -> Bitmap.CompressFormat.PNG
            isWebp -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSLESS else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
            else -> Bitmap.CompressFormat.JPEG
        }
        val quality = if (isPng) 100 else 96

        FileOutputStream(tempFile).use { out ->
            croppedBitmap.compress(format, quality, out)
            out.flush()
        }
        croppedBitmap.recycle()

        // 8. Tulis kembali metadata EXIF ke file temporer (minus orientation — pixel sudah
        //    fisik diputar & dipotong, jadi orientasi selalu NORMAL setelah ini)
        if (!isPng) {
            try {
                val tempExif = ExifInterface(tempFile.absolutePath)
                for ((tag, value) in exifAttributes) tempExif.setAttribute(tag, value)
                tempExif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                tempExif.saveAttributes()
            } catch (_: Exception) {}
        }

        // 9. Salin isi tempFile kembali ke MediaStore URI (menimpa file ASLI, bukan file baru)
        val newBytes = tempFile.readBytes()
        val outStream = contentResolver.openOutputStream(uri, "rwt")
            ?: contentResolver.openOutputStream(uri, "wt")
            ?: contentResolver.openOutputStream(uri, "w")
            ?: return@withContext CropResult(uri, false, "Tidak dapat membuka file penyimpanan untuk menulis. Pastikan izin telah diberikan.")
        outStream.use { out ->
            out.write(newBytes)
            out.flush()
        }

        // 10. Update database MediaStore — WIDTH/HEIGHT WAJIB diupdate (beda dari rotate,
        //     dimensi benar-benar berubah setelah crop), tanggal asli tetap dipertahankan.
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.ORIENTATION, 0)
            put(MediaStore.MediaColumns.SIZE, newBytes.size.toLong())
            put(MediaStore.MediaColumns.WIDTH, cropWidth)
            put(MediaStore.MediaColumns.HEIGHT, cropHeight)
            if (origDateAdded > 0L) put(MediaStore.MediaColumns.DATE_ADDED, origDateAdded)
            if (origDateTaken > 0L) put("datetaken", origDateTaken)
            if (origDateModified > 0L) put(MediaStore.MediaColumns.DATE_MODIFIED, origDateModified)
        }
        try { contentResolver.update(uri, values, null, null) } catch (_: Exception) {}
        try { contentResolver.notifyChange(uri, null) } catch (_: Exception) {}

        // 11. Invalidate semua layer cache (QuickPic Thumbnail Cache & Coil ImageLoader)
        thumbnailCache?.invalidate(uri)
        try {
            imageLoader.memoryCache?.clear()
            imageLoader.diskCache?.clear()
        } catch (_: Exception) {}

        return@withContext CropResult(uri, true)
    } catch (e: Throwable) {
        return@withContext CropResult(uri, false, "${e.javaClass.simpleName}: ${e.message ?: "Error tidak diketahui"}")
    } finally {
        if (tempFile.exists()) tempFile.delete()
    }
}

/**
 * Baca dimensi gambar & derajat rotasi EXIF TANPA decode penuh (bounds-only, cepat &
 * ringan), lalu kembalikan dimensi yang SUDAH ditukar (width<->height) jika EXIF bilang
 * gambar diputar 90/270 derajat — supaya ruang koordinat yang dipakai UI cocok dengan
 * orientasi yang benar-benar DITAMPILKAN (sama seperti bagaimana Coil menampilkannya),
 * bukan raw pixel data sebelum koreksi orientasi.
 */
suspend fun Context.readDisplayedImageGeometry(uri: Uri): DisplayedImageGeometry? = withContext(Dispatchers.IO) {
    runCatching {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, boundsOpts) }
        val rawWidth = boundsOpts.outWidth
        val rawHeight = boundsOpts.outHeight
        if (rawWidth <= 0 || rawHeight <= 0) return@runCatching null

        var exifDegrees = 0
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                exifDegrees = exifOrientationDegrees(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
            }
        } catch (_: Exception) {}

        if (exifDegrees == 90 || exifDegrees == 270) {
            DisplayedImageGeometry(displayedWidth = rawHeight, displayedHeight = rawWidth, exifDegrees = exifDegrees)
        } else {
            DisplayedImageGeometry(displayedWidth = rawWidth, displayedHeight = rawHeight, exifDegrees = exifDegrees)
        }
    }.getOrNull()
}

/** Dimensi gambar dalam orientasi yang DITAMPILKAN ke user (sudah dikoreksi EXIF). */
data class DisplayedImageGeometry(
    val displayedWidth: Int,
    val displayedHeight: Int,
    val exifDegrees: Int,
)

private fun exifOrientationDegrees(orientation: Int): Int = when (orientation) {
    ExifInterface.ORIENTATION_ROTATE_90 -> 90
    ExifInterface.ORIENTATION_ROTATE_180 -> 180
    ExifInterface.ORIENTATION_ROTATE_270 -> 270
    else -> 0
}
