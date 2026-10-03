package com.example.quickpic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.collection.LruCache
import java.io.File
import java.security.MessageDigest

/**
 * Persistent thumbnail cache stored under the app cache directory.
 * Android removes this directory when the user chooses Settings > Apps >
 * QuickPic > Clear cache, while normal app restarts keep the thumbnails.
 */
class ThumbnailCache private constructor(context: Context) {
    companion object {
        @Volatile private var instance: ThumbnailCache? = null

        /**
         * Satu instance yang dipakai bersama di seluruh app, sehingga layer
         * in-memory LRU (15% max heap) benar-benar shared antar grid/layar,
         * bukan cache terpisah-pisah per komponen.
         */
        fun getInstance(context: Context): ThumbnailCache = instance ?: synchronized(this) {
            instance ?: ThumbnailCache(context.applicationContext).also { instance = it }
        }
    }

    private val directory = File(context.applicationContext.cacheDir, "quickpic_thumbnails").apply { mkdirs() }
    private val maxBytes = 256L * 1024L * 1024L // 256 MB on disk

    // In‑memory LRU cache (bitmap) limited to ~15% of max heap (in MB)
    private val memoryCache: LruCache<String, Bitmap> = LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 1024 * 0.15).toInt().coerceAtLeast(16)
    )

    private fun key(uri: Uri, version: String): String = "$uri|$version"

    private fun uriHash(uri: Uri): String = sha256(uri.toString())

    private fun fileFor(uri: Uri, version: String): File =
        File(directory, "${uriHash(uri)}_${sha256(version)}.jpg")

    /** Return the cached file on disk if it exists. */
    fun existing(uri: Uri, version: String): File? =
        fileFor(uri, version).takeIf { it.isFile && it.length() > 0L }
            ?.also { touch(it) }

    /** Tandai file sebagai baru diakses agar trimIfNeeded() evict berbasis LRU, bukan urutan tulis. */
    private fun touch(file: File) {
        runCatching { file.setLastModified(System.currentTimeMillis()) }
    }

    /** Retrieve bitmap from memory cache, falling back to disk cache. */
    fun getBitmap(uri: Uri, version: String): Bitmap? {
        val k = key(uri, version)
        memoryCache.get(k)?.let { return it }
        val file = existing(uri, version) ?: return null
        return BitmapFactory.decodeFile(file.absolutePath)?.also { memoryCache.put(k, it) }
    }

    /** Store bitmap in both memory and disk caches. */
    fun putBitmap(uri: Uri, version: String, bitmap: Bitmap) {
        val k = key(uri, version)
        memoryCache.put(k, bitmap)
        save(uri, version, bitmap)
    }

    /**
     * Hapus seluruh cache untuk URI tertentu dari memory dan disk.
     * Dipanggil setelah rotasi permanen agar thumbnail langsung diregenerasi dari file baru.
     */
    fun invalidate(uri: Uri) {
        val uriPrefix = "$uri|"
        memoryCache.snapshot().keys
            .filter { it.startsWith(uriPrefix) }
            .forEach { memoryCache.remove(it) }

        val prefix = uriHash(uri)
        runCatching {
            directory.listFiles()
                ?.filter { it.name.startsWith(prefix) }
                ?.forEach { it.delete() }
        }
    }

    /** Hapus seluruh cache */
    fun clearAll() {
        memoryCache.evictAll()
        runCatching {
            directory.listFiles()?.forEach { it.delete() }
        }
    }

    private fun save(uri: Uri, version: String, bitmap: Bitmap) {
        val target = fileFor(uri, version)
        runCatching {
            target.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                out.flush()
            }
            trimIfNeeded()
        }
    }

    private fun trimIfNeeded() {
        val files = directory.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            total -= file.length()
            file.delete()
        }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
