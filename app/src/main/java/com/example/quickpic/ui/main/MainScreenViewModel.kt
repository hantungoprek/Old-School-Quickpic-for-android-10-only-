package com.example.quickpic.ui.main

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.quickpic.data.DataRepository
import com.example.quickpic.data.MediaFolder
import com.example.quickpic.data.MediaItem
import com.example.quickpic.data.MediaLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

enum class SortMode { NAME, DATE, FLOW }
enum class SortDirection { ASCENDING, DESCENDING }

class MainScreenViewModel(context: Context, dataRepository: DataRepository) : ViewModel() {
    private val preferences = context.getSharedPreferences("quickpic_preferences", Context.MODE_PRIVATE)
    private val refreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val sortMode = MutableStateFlow(
        runCatching { SortMode.valueOf(preferences.getString("sort_mode", SortMode.NAME.name) ?: SortMode.NAME.name) }
            .getOrDefault(SortMode.NAME)
    )
    private val sortDirection = MutableStateFlow(
        runCatching { SortDirection.valueOf(preferences.getString("sort_direction", SortDirection.ASCENDING.name) ?: SortDirection.ASCENDING.name) }
            .getOrDefault(SortDirection.ASCENDING)
    )

    val selectedSortMode: StateFlow<SortMode> = sortMode
    val selectedSortDirection: StateFlow<SortDirection> = sortDirection

    val uiState: StateFlow<MainScreenUiState> = refreshRequests
        .onStart { emit(Unit) }
        .flatMapLatest {
            dataRepository.data
                .combine(sortMode) { library, sort -> library to sort }
                .combine(sortDirection) { (library, sort), direction -> library.sorted(sort, direction) }
                .map<MediaLibrary, MainScreenUiState> { MainScreenUiState.Success(it) }
                // Query MediaStore (dataRepository.data) dan proses sorting-nya BUKAN pekerjaan
                // ringan untuk ribuan file — tanpa flowOn ini, semuanya berjalan di main thread
                // (viewModelScope default-nya Main), menyebabkan UI freeze/jank saat load awal
                // atau tiap kali refresh() dipanggil (rotate, hapus, rename, pindah, dll).
                .flowOn(Dispatchers.IO)
        }
        .catch { emit(MainScreenUiState.Error(it)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainScreenUiState.Loading)

    fun refresh() = refreshRequests.tryEmit(Unit)
    fun setSortMode(mode: SortMode) {
        sortMode.value = mode
        preferences.edit().putString("sort_mode", mode.name).apply()
    }

    fun setSortDirection(direction: SortDirection) {
        sortDirection.value = direction
        preferences.edit().putString("sort_direction", direction.name).apply()
    }
}

private fun MediaLibrary.sorted(mode: SortMode, direction: SortDirection): MediaLibrary = when (mode) {
    SortMode.NAME -> {
        val sortedFolders = folders.sortedWith(
            compareBy<MediaFolder> { it.displayName.lowercase() }
                .thenBy { it.path }
        )
        val sortedMedia = media.sortedWith(
            compareBy<MediaItem> { it.displayName.lowercase() }
                .thenBy { it.id }
        )
        copy(
            folders = pinStandardFolders(if (direction == SortDirection.DESCENDING) sortedFolders.asReversed() else sortedFolders),
            media = if (direction == SortDirection.DESCENDING) sortedMedia.asReversed() else sortedMedia,
        )
    }

    SortMode.DATE -> {
        val newestByFolder: Map<String, Long> = media
            .asSequence()
            .groupingBy { it.relativePath }
            .fold(0L) { newest, item -> maxOf(newest, item.effectiveDateSeconds.coerceAtLeast(0L)) }

        val sortedFolders = folders.sortedWith(
            compareBy<MediaFolder> { newestByFolder[it.path] ?: 0L }
                .thenBy { it.displayName.lowercase() }
                .thenBy { it.path }
        )
        val sortedMedia = media.sortedWith(
            compareBy<MediaItem> { it.effectiveDateSeconds.coerceAtLeast(0L) }
                .thenBy { it.displayName.lowercase() }
                .thenBy { it.id }
        )
        copy(
            folders = pinStandardFolders(if (direction == SortDirection.DESCENDING) sortedFolders.asReversed() else sortedFolders),
            media = if (direction == SortDirection.DESCENDING) sortedMedia.asReversed() else sortedMedia,
        )
    }

    SortMode.FLOW -> copy(
        folders = pinStandardFolders(folders.sortedBy { it.path }),
        media = media.sortedWith(
            compareBy<MediaItem> { it.relativePath.lowercase() }
                .thenBy { it.displayName.lowercase() }
                .thenBy { it.id }
        ),
    )

}

private fun pinStandardFolders(folders: List<MediaFolder>): List<MediaFolder> {
    // Keep Android's two most common camera/screenshot locations fixed at the top.
    // DCIM/Camera is the primary default; if it does not exist, fall back to DCIM/.
    val dcimCamera = folders.firstOrNull { it.path.equals("DCIM/Camera/", ignoreCase = true) }
    val dcim = folders.firstOrNull { it.path.equals("DCIM/", ignoreCase = true) }
    val screenshots = folders.firstOrNull {
        it.path.equals("Pictures/Screenshots/", ignoreCase = true) ||
            it.path.equals("Pictures/Screenshot/", ignoreCase = true)
    }
    val primaryDcim = dcimCamera ?: dcim
    val pinnedPaths = buildSet {
        primaryDcim?.let { add(it.path) }
        // If DCIM/ exists alongside DCIM/Camera/, keep the parent available but not pinned.
        screenshots?.let { add(it.path) }
    }
    val others = folders.filterNot { it.path in pinnedPaths }
    // Prioritaskan folder yang memiliki file di bagian atas,
    // sedangkan folder kosong (totalCount == 0) selalu diletakkan di bagian paling bawah.
    val (nonEmpty, empty) = others.partition { it.totalCount > 0 }
    return buildList {
        primaryDcim?.let(::add)
        screenshots?.let(::add)
        addAll(nonEmpty)
        addAll(empty)
    }
}

sealed interface MainScreenUiState {
    data object Loading : MainScreenUiState
    data class Error(val throwable: Throwable) : MainScreenUiState
    data class Success(val data: MediaLibrary) : MainScreenUiState
}
