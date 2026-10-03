package com.example.quickpic

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okio.Path.Companion.toOkioPath

class QuickPicApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader {
        val cacheDir = cacheDir.resolve("coil_thumbnails")
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.toOkioPath())
                    .maxSizeBytes(128L * 1024L * 1024L)
                    .build()
            }
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .crossfade(false)
            .build()
    }
}
