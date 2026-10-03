package com.example.quickpic.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

interface DataRepository {
    val data: Flow<MediaLibrary>
}

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val dateAddedSeconds: Long,
    val dateModifiedSeconds: Long = 0L,
    val dateTakenMillis: Long = 0L,
    val durationMillis: Long,
    val sizeBytes: Long,
    val relativePath: String,
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")

    /** Tanggal acuan terbaik untuk pengurutan tanggal (terbaru/terlama) */
    val effectiveDateSeconds: Long
        get() = when {
            dateTakenMillis > 0L -> dateTakenMillis / 1000L
            dateModifiedSeconds > 0L -> dateModifiedSeconds
            else -> dateAddedSeconds
        }
}

data class MediaFolder(
    val path: String,
    val displayName: String,
    val thumbnail: Uri,
    val photoCount: Int,
    val videoCount: Int,
) {
    val totalCount: Int get() = photoCount + videoCount
}

data class MediaLibrary(
    val folders: List<MediaFolder>,
    val media: List<MediaItem>,
)

class DefaultDataRepository(private val context: Context) : DataRepository {
    private val contentResolver = context.applicationContext.contentResolver

    override val data: Flow<MediaLibrary> = flow {
        emit(contentResolver.loadMediaLibrary(context.applicationContext))
    }
}

private fun ContentResolver.loadMediaLibrary(context: Context? = null): MediaLibrary {
    val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
    val projection = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.DISPLAY_NAME,
        MediaStore.Files.FileColumns.MIME_TYPE,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.Files.FileColumns.DATE_ADDED,
        MediaStore.Files.FileColumns.DATE_MODIFIED,
        "datetaken", // MediaStore.Images.Media.DATE_TAKEN / MediaStore.Video.Media.DATE_TAKEN
        MediaStore.Files.FileColumns.DURATION,
        MediaStore.Files.FileColumns.SIZE,
        MediaStore.Files.FileColumns.RELATIVE_PATH,
    )

    val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=?"
    val selectionArgs = arrayOf(
        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
    )
    val sortOrder = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"

    val media = query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
        val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
        val mimeTypeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
        val mediaTypeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
        val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
        val dateModifiedColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
        val dateTakenColumn = cursor.getColumnIndex("datetaken")
        val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DURATION)
        val sizeColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
        val relativePathColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)

        buildList {
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val mimeType = cursor.getString(mimeTypeColumn).orEmpty()
                val mediaType = cursor.getInt(mediaTypeColumn)
                val relativePath = if (relativePathColumn >= 0) {
                    cursor.getString(relativePathColumn).orEmpty()
                } else {
                    ""
                }

                // Ignore any unexpected MediaStore row that slipped through the selection.
                if (mediaType != MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE &&
                    mediaType != MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                ) continue

                val dateTaken = if (dateTakenColumn >= 0 && !cursor.isNull(dateTakenColumn)) {
                    cursor.getLong(dateTakenColumn)
                } else {
                    0L
                }

                add(
                    MediaItem(
                        id = id,
                        // Querying Files is useful for one combined image/video
                        // library, but the URI handed to the rest of the app must
                        // remain in its real media collection. In particular,
                        // opening a Video URI is more reliable than opening the
                        // read-only Files aggregation URI on scoped storage.
                        uri = ContentUris.withAppendedId(
                            if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) {
                                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                            } else {
                                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                            },
                            id,
                        ),
                        displayName = cursor.getString(nameColumn) ?: "Untitled media",
                        mimeType = mimeType,
                        dateAddedSeconds = cursor.getLong(dateAddedColumn),
                        dateModifiedSeconds = if (dateModifiedColumn >= 0 && !cursor.isNull(dateModifiedColumn)) cursor.getLong(dateModifiedColumn) else 0L,
                        dateTakenMillis = dateTaken,
                        durationMillis = if (cursor.isNull(durationColumn)) 0L else cursor.getLong(durationColumn),
                        sizeBytes = if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) cursor.getLong(sizeColumn) else 0L,
                        relativePath = relativePath.ifBlank { "Pictures/" },
                    )
                )
            }
        }
    } ?: emptyList()

    val nonEmptyFolders = media
        .groupBy { it.relativePath }
        .map { (path, items) ->
            val folderName = path
                .trimEnd('/')
                .substringAfterLast('/')
                .ifBlank { "Internal storage" }

            // Selalu ambil foto/video terbaru berdasarkan tanggal pengambilan (effectiveDateSeconds)
            val newestItem = items.maxByOrNull { it.effectiveDateSeconds } ?: items.first()

            MediaFolder(
                path = path,
                displayName = folderName,
                thumbnail = newestItem.uri,
                photoCount = items.count { !it.isVideo },
                videoCount = items.count { it.isVideo },
            )
        }

    val emptyFolders = scanEmptyFolders(context, nonEmptyFolders.map { it.path }.toSet())
    val allFolders = (nonEmptyFolders + emptyFolders).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })

    return MediaLibrary(folders = allFolders, media = media)
}

private fun scanEmptyFolders(context: Context?, existingPaths: Set<String>): List<MediaFolder> {
    val emptyFolders = mutableListOf<MediaFolder>()
    val visitedPaths = existingPaths.map { it.trimEnd('/').lowercase() }.toMutableSet()

    fun checkAndAdd(dir: File, relativePath: String) {
        val normalized = relativePath.trimEnd('/').lowercase()
        if (dir.exists() && dir.isDirectory && !dir.name.startsWith(".") && !visitedPaths.contains(normalized)) {
            visitedPaths.add(normalized)
            emptyFolders.add(
                MediaFolder(
                    path = if (relativePath.endsWith("/")) relativePath else "$relativePath/",
                    displayName = dir.name,
                    thumbnail = Uri.EMPTY,
                    photoCount = 0,
                    videoCount = 0,
                )
            )
        }
    }

    // 1. Scan created folders from SharedPreferences (if any)
    context?.let { ctx ->
        val prefs = ctx.getSharedPreferences("app_folders", Context.MODE_PRIVATE)
        val created = prefs.getStringSet("created_folders", emptySet()).orEmpty()
        for (relPath in created) {
            val file = File(Environment.getExternalStorageDirectory(), relPath.trimEnd('/'))
            checkAndAdd(file, relPath)
        }
    }

    // 2. Scan standard media roots for empty folders
    val rootDirs = listOf(
        Pair(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Pictures"),
        Pair(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "DCIM"),
        Pair(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Movies"),
    )

    for ((baseDir, baseName) in rootDirs) {
        if (baseDir != null && baseDir.exists() && baseDir.isDirectory) {
            baseDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.forEach { subDir ->
                checkAndAdd(subDir, "$baseName/${subDir.name}/")
            }
        }
    }

    // 3. Scan top-level directories in storage
    val externalRoot = Environment.getExternalStorageDirectory()
    if (externalRoot != null && externalRoot.exists()) {
        externalRoot.listFiles()?.filter {
            it.isDirectory &&
                !it.name.startsWith(".") &&
                !it.name.equals("Android", ignoreCase = true) &&
                !it.name.equals("Pictures", ignoreCase = true) &&
                !it.name.equals("DCIM", ignoreCase = true) &&
                !it.name.equals("Movies", ignoreCase = true)
        }?.forEach { topDir ->
            checkAndAdd(topDir, "${topDir.name}/")
        }
    }

    return emptyFolders.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
}
