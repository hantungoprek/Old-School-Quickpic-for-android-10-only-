package com.example.quickpic.ui.main

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.content.ContentValues
import android.content.ContentUris
import android.media.MediaScannerConnection
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.example.quickpic.ThumbnailCache
import com.example.quickpic.rotateMediaFilePermanently

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.request.ImageRequest
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import coil.imageLoader
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import java.io.File
import java.io.InputStream

private enum class HomeTab { Folders, Photos, Videos }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val viewModel: MainScreenViewModel = viewModel { MainScreenViewModel(context.applicationContext, com.example.quickpic.data.DefaultDataRepository(context.applicationContext)) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val hasPermission = context.hasMediaPermission()
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { viewModel.refresh() }

    if (!hasPermission) {
        PermissionRequired(modifier) { permissionLauncher.launch(mediaPermissions()) }
        return
    }

    when (val current = state) {
        MainScreenUiState.Loading -> Loading(modifier)
        is MainScreenUiState.Error -> ErrorMessage(current.throwable.message ?: "Unable to load media.", modifier)
        is MainScreenUiState.Success -> LibraryContent(current.data, viewModel, modifier)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryContent(library: com.example.quickpic.data.MediaLibrary, viewModel: MainScreenViewModel, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by rememberSaveable { mutableIntStateOf(HomeTab.Folders.ordinal) }
    var openFolderPath by rememberSaveable { mutableStateOf<String?>(null) }
    var viewerItems by remember { mutableStateOf<List<com.example.quickpic.data.MediaItem>>(emptyList()) }
    var viewerIndex by remember { mutableIntStateOf(0) }
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var aboutOpen by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    var selectionOverflowOpen by remember { mutableStateOf(false) }
    var rotateSelectionOpen by remember { mutableStateOf(false) }
    var rotateBusy by remember { mutableStateOf(false) }
    var rotateError by remember { mutableStateOf<String?>(null) }
    // Item yang menunggu write permission di Android 11+ sebelum diproses ulang
    var pendingRotateItems by remember { mutableStateOf<List<com.example.quickpic.data.MediaItem>>(emptyList()) }
    var pendingRotateDegrees by remember { mutableIntStateOf(0) }
    var sortOpen by remember { mutableStateOf(false) }
    var dateSortOpen by remember { mutableStateOf(false) }
    var nameSortOpen by remember { mutableStateOf(false) }
    var rotationOpen by remember { mutableStateOf(false) }
    var rotationTargetItem by remember { mutableStateOf<com.example.quickpic.data.MediaItem?>(null) }
    var detailsItem by remember { mutableStateOf<com.example.quickpic.data.MediaItem?>(null) }
    var cropTargetItem by remember { mutableStateOf<com.example.quickpic.data.MediaItem?>(null) }
    var renameFolder by remember { mutableStateOf<com.example.quickpic.data.MediaFolder?>(null) }
    var renameItem by remember { mutableStateOf<com.example.quickpic.data.MediaItem?>(null) }
    var renameError by remember { mutableStateOf<String?>(null) }
    var createFolderOpen by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var deleteItems by remember { mutableStateOf<List<com.example.quickpic.data.MediaItem>>(emptyList()) }
    var pendingDeleteUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var pendingMoveSourceUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var copyItems by remember { mutableStateOf<List<com.example.quickpic.data.MediaItem>>(emptyList()) }
    var copyDestinationOpen by remember { mutableStateOf(false) }
    var copyBusy by remember { mutableStateOf(false) }
    var copyFailureMessage by remember { mutableStateOf<String?>(null) }
    var pendingTreeCopyItems by remember { mutableStateOf<List<com.example.quickpic.data.MediaItem>>(emptyList()) }
    var moveItems by remember { mutableStateOf<List<com.example.quickpic.data.MediaItem>>(emptyList()) }
    var moveDestinationOpen by remember { mutableStateOf(false) }
    var moveBusy by remember { mutableStateOf(false) }
    var moveFailureMessage by remember { mutableStateOf<String?>(null) }
    var pendingTreeMoveItems by remember { mutableStateOf<List<com.example.quickpic.data.MediaItem>>(emptyList()) }
    var pendingTreeMoveDestinationPath by remember { mutableStateOf<String?>(null) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedMediaIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var rotationRevision by remember { mutableLongStateOf(0L) }
    val photoRotations = remember { mutableStateMapOf<String, Int>() }
    val thumbnailCacheForRotation = remember { ThumbnailCache.getInstance(context) }

    // Helper untuk menjalankan rotasi permanen ke file
    val performPermanentRotation: (List<com.example.quickpic.data.MediaItem>, Int) -> Unit = { items, deg ->
        if (items.isNotEmpty() && deg != 0) {
            rotateBusy = true
            scope.launch {
                val errors = mutableListOf<String>()
                var successCount = 0
                for (item in items) {
                    val res = context.rotateMediaFilePermanently(item.uri, deg, thumbnailCacheForRotation)
                    if (res.success) {
                        successCount++
                        // Reset rotasi virtual in-app karena file aslinya sudah berputar secara fisik di storage
                        photoRotations.remove(item.uri.toString())
                    } else {
                        errors.add("${item.displayName}: ${res.errorMessage}")
                    }
                }
                if (successCount > 0) {
                    rotationRevision = System.currentTimeMillis()
                }
                rotateBusy = false
                viewModel.refresh()
                scope.launch {
                    delay(500)
                    viewModel.refresh()
                }
                if (errors.isEmpty()) {
                    android.widget.Toast.makeText(context, "$successCount file berhasil diputar permanen.", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    rotateError = errors.joinToString("\n")
                }
            }
        }
    }

    val requestRotateItems: (List<com.example.quickpic.data.MediaItem>, Int) -> Unit = { items, deg ->
        if (items.isNotEmpty() && deg != 0) {
            performPermanentRotation(items, deg)
        }
    }

    // Dideklarasikan lebih dulu agar callback launcher dapat memanggil launcher
    // yang sama saat Android 10 meminta persetujuan penghapusan berikutnya.
    var deleteLauncher: androidx.activity.result.ActivityResultLauncher<IntentSenderRequest>? = null
    deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val moving = pendingMoveSourceUris.isNotEmpty()
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && result.resultCode == Activity.RESULT_OK && pendingDeleteUris.isNotEmpty()) {
            // Android 10 memberikan izin untuk item yang memicu dialog.
            // Lanjutkan hanya dari daftar yang BELUM selesai; jangan mengulang
            // URI yang sudah berhasil dihapus karena delete() akan mengembalikan 0.
            val completed = context.deleteMediaItems(
                pendingDeleteUris,
                deleteLauncher,
                onPermissionRequired = { remaining, sender ->
                    pendingDeleteUris = remaining
                    deleteLauncher?.launch(IntentSenderRequest.Builder(sender).build())
                },
            )
            if (completed) {
                pendingDeleteUris = emptyList()
                pendingMoveSourceUris = emptyList()
                moveBusy = false
                selectionMode = false
                selectedMediaIds = emptySet()
                viewModel.refresh()
                scope.launch { delay(750); viewModel.refresh() }
                if (moving) {
                    moveItems = emptyList()
                    android.widget.Toast.makeText(context, "File berhasil dipindahkan.", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            // Android 11+ menyelesaikan seluruh batch lewat satu dialog sistem.
            // Jika pengguna membatalkan, jangan mengubah selection.
            pendingDeleteUris = emptyList()
            if (result.resultCode == Activity.RESULT_OK) {
                pendingMoveSourceUris = emptyList()
                moveBusy = false
                selectionMode = false
                selectedMediaIds = emptySet()
                viewModel.refresh()
                scope.launch { delay(750); viewModel.refresh() }
                if (moving) {
                    moveItems = emptyList()
                    android.widget.Toast.makeText(context, "File berhasil dipindahkan.", android.widget.Toast.LENGTH_SHORT).show()
                }
            } else if (moving) {
                pendingMoveSourceUris = emptyList()
                moveBusy = false
                android.widget.Toast.makeText(context, "Penghapusan sumber dibatalkan; file hasil salin tetap ada.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
        deleteItems = emptyList()
    }
    val sortMode by viewModel.selectedSortMode.collectAsStateWithLifecycle()
    val sortDirection by viewModel.selectedSortDirection.collectAsStateWithLifecycle()

    val completeCopy: (CopyResult) -> Unit = { result ->
        copyBusy = false
        if (result.failed == 0) {
            copyDestinationOpen = false
            copyItems = emptyList()
            selectionMode = false
            selectedMediaIds = emptySet()
            viewModel.refresh()
            scope.launch { delay(750); viewModel.refresh() }
            android.widget.Toast.makeText(context, "${result.copied} file berhasil disalin.", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            viewModel.refresh()
            copyFailureMessage = result.failureMessage()
            android.widget.Toast.makeText(
                context,
                if (result.copied > 0) {
                    "${result.copied} file disalin, ${result.failed} gagal. Detail kegagalan ditampilkan."
                } else {
                    "File tidak dapat disalin. Detail kegagalan ditampilkan."
                },
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }
    val requestMoveDeletion: (List<com.example.quickpic.data.MediaItem>) -> Unit = { sourceItems ->
        pendingMoveSourceUris = sourceItems.map { it.uri }
        moveDestinationOpen = false
        val completedImmediately = context.deleteMediaItems(
            pendingMoveSourceUris,
            deleteLauncher,
            onPermissionRequired = { remaining, sender ->
                pendingDeleteUris = remaining
                deleteLauncher?.launch(IntentSenderRequest.Builder(sender).build())
            },
        )
        if (completedImmediately) {
            pendingMoveSourceUris = emptyList()
            moveBusy = false
            moveItems = emptyList()
            selectionMode = false
            selectedMediaIds = emptySet()
            viewModel.refresh()
            scope.launch { delay(750); viewModel.refresh() }
            android.widget.Toast.makeText(context, "${sourceItems.size} file berhasil dipindahkan.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val completePreparedMove: (CopyResult, List<com.example.quickpic.data.MediaItem>) -> Unit = { result, sourceItems ->
        if (result.failed == 0) {
            // A move is deliberately copy-then-delete. The source is only
            // removed after the destination has been reopened and verified.
            requestMoveDeletion(sourceItems)
        } else {
            moveBusy = false
            viewModel.refresh()
            moveFailureMessage = result.failureMessage("dipindahkan")
            android.widget.Toast.makeText(context, "Pemindahan gagal. Sumber tidak dihapus.", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    val treeCopyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        val itemsToCopy = pendingTreeCopyItems
        pendingTreeCopyItems = emptyList()
        if (treeUri == null || itemsToCopy.isEmpty()) {
            copyBusy = false
            android.widget.Toast.makeText(context, "Pemilihan folder dibatalkan.", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    context.copyMediaItemsToTree(itemsToCopy, treeUri)
                }
                completeCopy(result)
            }
        }
    }
    val treeMoveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        val itemsToMove = pendingTreeMoveItems
        val destinationPath = pendingTreeMoveDestinationPath
        pendingTreeMoveItems = emptyList()
        pendingTreeMoveDestinationPath = null
        if (treeUri == null || itemsToMove.isEmpty()) {
            moveBusy = false
            android.widget.Toast.makeText(context, "Pemilihan folder dibatalkan.", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    context.copyMediaItemsToTree(itemsToMove, treeUri, destinationPath)
                }
                completePreparedMove(result, itemsToMove)
            }
        }
    }

    if (settingsOpen) {
        SettingsScreen(onBack = { settingsOpen = false })
        return
    }

    val selectedFolder = openFolderPath?.let { path -> library.folders.firstOrNull { it.path == path } }
    val title = selectedFolder?.displayName ?: "Old School QuickPic"
    val drawerState = rememberDrawerState(if (drawerOpen) DrawerValue.Open else DrawerValue.Closed)
    if (drawerOpen && drawerState.currentValue == DrawerValue.Closed) LaunchedEffect(Unit) { drawerState.open() }
    if (!drawerOpen && drawerState.currentValue == DrawerValue.Open) LaunchedEffect(Unit) { drawerState.close() }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(16.dp))
                Text("Old School QuickPic", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
                HorizontalDivider()
                DrawerItem("Folder", Icons.Default.Folder, selectedFolder == null && selectedTab == HomeTab.Folders.ordinal) { selectedTab = HomeTab.Folders.ordinal; openFolderPath = null; drawerOpen = false }
                DrawerItem("Momen", Icons.Default.CalendarMonth, false) { selectedTab = HomeTab.Photos.ordinal; openFolderPath = null; drawerOpen = false }
                DrawerItem("Tambah", Icons.Default.Add, false) { drawerOpen = false; createFolderOpen = true }
                DrawerItem("Pengaturan", Icons.Default.Settings, false) { drawerOpen = false; settingsOpen = true }
                DrawerItem("Tentang", Icons.Default.Info, false) { drawerOpen = false; aboutOpen = true }
            }
        },
    ) {
        Scaffold(
            modifier = modifier,
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        if (selectionMode) {
                            IconButton(onClick = {
                                selectionMode = false
                                selectedMediaIds = emptySet()
                            }) { Icon(Icons.Default.Close, "Batal memilih") }
                        } else if (selectedFolder != null) {
                            IconButton(onClick = { openFolderPath = null }) { Icon(Icons.Default.ArrowBack, "Kembali") }
                        } else IconButton(onClick = { drawerOpen = true }) { Icon(Icons.Default.Menu, "Menu") }
                    },
                    title = {
                        if (selectionMode) Text("${selectedMediaIds.size} dipilih")
                        else Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    actions = {
                        if (selectionMode) {
                            val selectedItems = library.media.filter { it.id in selectedMediaIds }

                            // Tombol aksi utama dibuat langsung di TopAppBar, mengikuti alur QuickPic lama.
                            IconButton(
                                onClick = { context.shareMedia(selectedItems.map { it.uri }) },
                                enabled = selectedItems.isNotEmpty(),
                            ) {
                                Icon(Icons.Default.Share, "Bagikan")
                            }
                            IconButton(
                                onClick = { deleteItems = selectedItems },
                                enabled = selectedItems.isNotEmpty(),
                            ) {
                                Icon(Icons.Default.Delete, "Hapus")
                            }
                            IconButton(onClick = {
                                val folderMedia = selectedFolder?.let { folder -> library.media.filter { it.relativePath == folder.path } } ?: emptyList()
                                selectedMediaIds = if (selectedMediaIds.size == folderMedia.size) emptySet() else folderMedia.map { it.id }.toSet()
                            }) { Icon(Icons.Default.SelectAll, "Pilih semua") }
                            Box {
                                IconButton(onClick = { selectionOverflowOpen = true }) { Icon(Icons.Default.MoreVert, "Menu lainnya") }
                                DropdownMenu(
                                    expanded = selectionOverflowOpen,
                                    onDismissRequest = { selectionOverflowOpen = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Rincian") },
                                        leadingIcon = { Icon(Icons.Default.Info, null) },
                                        enabled = selectedItems.size == 1,
                                        onClick = {
                                            selectionOverflowOpen = false
                                            selectedItems.firstOrNull()?.let { detailsItem = it }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Pindah ke") },
                                        leadingIcon = { Icon(Icons.Default.DriveFileMove, null) },
                                        enabled = selectedItems.isNotEmpty(),
                                        onClick = {
                                            selectionOverflowOpen = false
                                            moveItems = selectedItems
                                            moveDestinationOpen = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Salin ke") },
                                        leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                                        enabled = selectedItems.isNotEmpty(),
                                        onClick = {
                                            selectionOverflowOpen = false
                                            copyItems = selectedItems
                                            copyDestinationOpen = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Ganti nama") },
                                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                                        enabled = selectedItems.size == 1,
                                        onClick = {
                                            selectionOverflowOpen = false
                                            selectedItems.firstOrNull()?.let { renameItem = it }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Ubah") },
                                        leadingIcon = { Icon(Icons.Default.Tune, null) },
                                        enabled = selectedItems.isNotEmpty(),
                                        onClick = {
                                            selectionOverflowOpen = false
                                            rotateSelectionOpen = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Pilih semua") },
                                        leadingIcon = { Icon(Icons.Default.SelectAll, null) },
                                        onClick = {
                                            selectionOverflowOpen = false
                                            val folderMedia = selectedFolder?.let { folder -> library.media.filter { it.relativePath == folder.path } } ?: emptyList()
                                            selectedMediaIds = folderMedia.map { it.id }.toSet()
                                        },
                                    )
                                }
                            }
                        } else if (selectedFolder != null) {
                            IconButton(onClick = {
                                selectionMode = true
                                selectedMediaIds = emptySet()
                            }) { Icon(Icons.Default.Checklist, "Tandai") }
                        }
                        if (!selectionMode) Box {
                            IconButton(onClick = { overflowOpen = true }) { Icon(Icons.Default.MoreVert, "Menu lainnya") }
                            DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                                OverflowItem("Tampilan", Icons.Default.GridView) { overflowOpen = false }
                                OverflowItem("Urutkan", Icons.Default.Sort) { overflowOpen = false; sortOpen = true }
                                OverflowItem("Tambah folder", Icons.Default.CreateNewFolder) { overflowOpen = false; createFolderOpen = true }
                                if (selectedFolder == null) OverflowItem("Muat tersembunyi", Icons.Default.VisibilityOff) { overflowOpen = false }
                                if (selectedFolder != null) {
                                    OverflowItem("Sembunyikan", Icons.Default.VisibilityOff) { overflowOpen = false }
                                    OverflowItem("Sembunyikan Folder", Icons.Default.FolderOff) { overflowOpen = false }
                                    OverflowItem("Ganti nama", Icons.Default.Edit) { overflowOpen = false; selectedFolder?.let { renameFolder = it } }
                                    OverflowItem("Perbaiki waktu", Icons.Default.Schedule) { overflowOpen = false }
                                    OverflowItem("Tautkan ke beranda", Icons.Default.Home) { overflowOpen = false }
                                }
                                OverflowItem("Pengaturan", Icons.Default.Settings) { overflowOpen = false; settingsOpen = true }
                            }
                        }
                    },
                )
            },
        ) { innerPadding ->
            if (selectedFolder != null) {
                val folderMedia = library.media.filter { it.relativePath == selectedFolder.path }
                MediaGrid(
                    media = folderMedia,
                    modifier = Modifier.padding(innerPadding),
                    rotationDegrees = { uri -> photoRotations[uri.toString()] ?: 0 },
                    rotationRevision = rotationRevision,
                    selectionMode = selectionMode,
                    selectedMediaIds = selectedMediaIds,
                    onMediaClick = { index ->
                        val item = folderMedia[index]
                        if (selectionMode) {
                            selectedMediaIds = if (item.id in selectedMediaIds) {
                                selectedMediaIds - item.id
                            } else {
                                selectedMediaIds + item.id
                            }
                        } else {
                            viewerItems = folderMedia
                            viewerIndex = index
                        }
                    },
                )
            } else {
                Column(Modifier.fillMaxSize().padding(innerPadding)) {
                    TabRow(selectedTabIndex = selectedTab) {
                        HomeTab.entries.forEachIndexed { index, tab ->
                            Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(tab.label()) }, icon = { Icon(tab.icon(), null) })
                        }
                    }
                    when (HomeTab.entries[selectedTab]) {
                        HomeTab.Folders -> FolderGrid(library.folders, rotationDegrees = { uri -> photoRotations[uri.toString()] ?: 0 }, rotationRevision = rotationRevision) { openFolderPath = it.path }
                        HomeTab.Photos -> { val list = library.media.filterNot { it.isVideo }; MediaGrid(list, Modifier.fillMaxSize(), rotationDegrees = { uri -> photoRotations[uri.toString()] ?: 0 }, rotationRevision = rotationRevision, onMediaClick = { viewerItems = list; viewerIndex = it }) }
                        HomeTab.Videos -> { val list = library.media.filter { it.isVideo }; MediaGrid(list, Modifier.fillMaxSize(), rotationDegrees = { uri -> photoRotations[uri.toString()] ?: 0 }, rotationRevision = rotationRevision, onMediaClick = { viewerItems = list; viewerIndex = it }) }
                    }
                }
            }
        }
    }

    if (viewerItems.isNotEmpty()) MediaViewer(
        viewerItems,
        viewerIndex,
        onDismiss = { viewerItems = emptyList() },
        onRotateRequest = { targetItem ->
            rotationTargetItem = targetItem
            rotationOpen = true
        },
        onDetailsRequest = { if (viewerItems.isNotEmpty()) detailsItem = viewerItems[viewerIndex] },
        onRenameRequest = { if (viewerItems.isNotEmpty()) renameItem = viewerItems[viewerIndex] },
        onMoveRequest = {
            if (viewerItems.isNotEmpty()) {
                moveItems = listOf(viewerItems[viewerIndex])
                moveDestinationOpen = true
            }
        },
        onCropRequest = { targetItem -> cropTargetItem = targetItem },
        rotationDegrees = { uri -> photoRotations[uri.toString()] ?: 0 },
        rotationRevision = rotationRevision,
    )

    // Editor Crop — dibuka dari menu "Ubah" di overflow MediaViewer.
    cropTargetItem?.let { item ->
        CropScreen(
            item = item,
            onDismiss = { cropTargetItem = null },
            onConfirm = {
                cropTargetItem = null
                rotationRevision = System.currentTimeMillis()
                viewModel.refresh()
            },
        )
    }

    if (sortOpen) {
        SortDialog(
            current = sortMode,
            onDismiss = { sortOpen = false },
        ) { mode ->
            sortOpen = false
            when (mode) {
                SortMode.NAME -> nameSortOpen = true
                SortMode.DATE -> dateSortOpen = true
                SortMode.FLOW -> viewModel.setSortMode(SortMode.FLOW)
            }
        }
    }
    if (nameSortOpen) {
        NameSortDialog(sortDirection) { direction ->
            viewModel.setSortMode(SortMode.NAME)
            viewModel.setSortDirection(direction)
            nameSortOpen = false
        }
    }
    if (dateSortOpen) {
        DateSortDialog(sortDirection) { direction ->
            viewModel.setSortMode(SortMode.DATE)
            viewModel.setSortDirection(direction)
            dateSortOpen = false
        }
    }
    if (aboutOpen) AlertDialog(onDismissRequest = { aboutOpen = false }, title = { Text("Tentang") }, text = { Text("Old School QuickPic\nGaleri foto/video offline bergaya QuickPic klasik.") }, confirmButton = { TextButton(onClick = { aboutOpen = false }) { Text("Tutup") } })
    detailsItem?.let { item -> MediaDetailsDialog(item) { detailsItem = null } }
    if (copyDestinationOpen) {
        CopyDestinationDialog(
            title = "Salin ke",
            folders = library.folders,
            enabled = !copyBusy,
            onDismiss = {
                if (!copyBusy) {
                    copyDestinationOpen = false
                    copyItems = emptyList()
                }
            },
            onNewFolderClick = { createFolderOpen = true },
            onFolderSelected = { destination ->
                if (!copyBusy) {
                    val itemsToCopy = copyItems
                    copyBusy = true
                    val canDirectAccess = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager())
                    val needsSaf = !canDirectAccess && itemsToCopy.any { !isMediaStoreInsertAllowed(destination.path, it.isVideo) }

                    if (needsSaf) {
                        pendingTreeCopyItems = itemsToCopy
                        android.widget.Toast.makeText(
                            context,
                            "Pilih folder ${destination.displayName} pada pemilih folder Android.",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                        treeCopyLauncher.launch(null)
                    } else {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                context.copyMediaItems(itemsToCopy, destination.path)
                            }
                            completeCopy(result)
                        }
                    }
                }
            },
        )
    }
    if (moveDestinationOpen) {
        CopyDestinationDialog(
            title = "Pindah ke",
            folders = library.folders,
            enabled = !moveBusy,
            onDismiss = {
                if (!moveBusy) {
                    moveDestinationOpen = false
                    moveItems = emptyList()
                }
            },
            onNewFolderClick = { createFolderOpen = true },
            onFolderSelected = { destination ->
                if (!moveBusy) {
                    val itemsToMove = moveItems
                    moveBusy = true
                    val canDirectAccess = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager())
                    val needsSaf = !canDirectAccess && itemsToMove.any { !isMediaStoreInsertAllowed(destination.path, it.isVideo) }

                    if (needsSaf) {
                        pendingTreeMoveItems = itemsToMove
                        pendingTreeMoveDestinationPath = destination.path
                        android.widget.Toast.makeText(
                            context,
                            "Pilih folder ${destination.displayName} pada pemilih folder Android.",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                        treeMoveLauncher.launch(null)
                    } else {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                context.copyMediaItems(itemsToMove, destination.path)
                            }
                            completePreparedMove(result, itemsToMove)
                        }
                    }
                }
            },
        )
    }
    copyFailureMessage?.let { message ->
        CopyFailureDialog(message = message) { copyFailureMessage = null }
    }
    moveFailureMessage?.let { message ->
        CopyFailureDialog(title = "Pindah gagal", message = message) { moveFailureMessage = null }
    }

    if (createFolderOpen) {
        AlertDialog(
            onDismissRequest = {
                createFolderOpen = false
                newFolderName = ""
            },
            title = { Text("Buat folder baru") },
            text = {
                Column {
                    Text(
                        "Folder baru akan dibuat di Pictures.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = { newFolderName = it },
                        label = { Text("Nama folder") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    createFolderOpen = false
                    newFolderName = ""
                }) { Text("Batal") }
            },
            confirmButton = {
                Button(
                    enabled = newFolderName.trim().isNotBlank(),
                    onClick = {
                        val name = newFolderName.trim()
                        createFolderOpen = false
                        newFolderName = ""
                        val folderDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), name)
                        if (!folderDir.exists()) {
                            folderDir.mkdirs()
                        }
                        val relativePath = "Pictures/$name/"
                        val prefs = context.getSharedPreferences("app_folders", Context.MODE_PRIVATE)
                        val set = prefs.getStringSet("created_folders", emptySet())?.toMutableSet() ?: mutableSetOf()
                        set.add(relativePath)
                        prefs.edit().putStringSet("created_folders", set).apply()
                        viewModel.refresh()
                        android.widget.Toast.makeText(context, "Folder '$name' berhasil dibuat.", android.widget.Toast.LENGTH_SHORT).show()
                    },
                ) {
                    Text("Buat")
                }
            },
        )
    }

    if (deleteItems.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { deleteItems = emptyList() },
            title = { Text("Hapus file?") },
            text = {
                Text(
                    if (deleteItems.size == 1)
                        "File ini akan dihapus dari perangkat."
                    else
                        "${deleteItems.size} file akan dihapus dari perangkat."
                )
            },
            dismissButton = { TextButton(onClick = { deleteItems = emptyList() }) { Text("Batal") } },
            confirmButton = {
                TextButton(onClick = {
                    val itemsToDelete = deleteItems
                    deleteItems = emptyList()
                    val urisToDelete = itemsToDelete.map { it.uri }.distinct()
                    pendingDeleteUris = urisToDelete
                    if (context.deleteMediaItems(
                            urisToDelete,
                            deleteLauncher,
                            onPermissionRequired = { remaining, sender ->
                                pendingDeleteUris = remaining
                                deleteLauncher?.launch(IntentSenderRequest.Builder(sender).build())
                            },
                        )
                    ) {
                        pendingDeleteUris = emptyList()
                        selectionMode = false
                        selectedMediaIds = emptySet()
                        viewModel.refresh()
                    }
                }) { Text("Hapus") }
            },
        )
    }
    renameFolder?.let { folder ->
        RenameDialog(
            title = "Ganti nama folder",
            initialName = folder.displayName,
            errorMessage = renameError,
            onDismiss = { renameFolder = null; renameError = null },
            onRename = { newName ->
                val result = context.renameMediaFolder(folder, newName)
                if (result == null) {
                    renameFolder = null
                    renameError = null
                    viewModel.refresh()
                    val parent = folder.path.trimEnd('/').substringBeforeLast('/', missingDelimiterValue = "")
                    openFolderPath = if (parent.isBlank()) "${newName.trim()}/" else "$parent/${newName.trim()}/"
                } else renameError = result
            },
        )
    }
    renameItem?.let { item ->
        RenameDialog(
            title = "Ganti nama",
            initialName = item.displayName,
            errorMessage = renameError,
            onDismiss = { renameItem = null; renameError = null },
            onRename = { newName ->
                val result = context.renameMediaItem(item, newName)
                if (result == null) {
                    renameItem = null
                    renameError = null
                    viewerItems = emptyList()
                    viewModel.refresh()
                } else renameError = result
            },
        )
    }
    // Dialog rotasi dari MediaViewer (rotasi PERMANEN ke file)
    if (rotationOpen) {
        val target = rotationTargetItem ?: viewerItems.getOrNull(viewerIndex)
        RotationDialog { degrees ->
            rotationOpen = false
            rotationTargetItem = null
            if (degrees != 0 && target != null) {
                requestRotateItems(listOf(target), degrees)
            }
        }
    }
    // Dialog rotasi untuk selection mode (menu Ubah) — rotasi PERMANEN ke file
    if (rotateSelectionOpen) {
        val selectedItems = library.media.filter { it.id in selectedMediaIds }
        RotationDialog { degrees ->
            rotateSelectionOpen = false
            if (degrees != 0 && selectedItems.isNotEmpty()) {
                requestRotateItems(selectedItems, degrees)
            }
        }
    }
    // Progress dialog saat rotasi sedang berjalan
    if (rotateBusy) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Memutar foto...") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(32.dp))
                    Spacer(Modifier.width(16.dp))
                    Text("Sedang memproses, harap tunggu.")
                }
            },
            confirmButton = {},
            properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false),
        )
    }
    // Error dialog jika ada file yang gagal diputar
    if (rotateError != null) {
        AlertDialog(
            onDismissRequest = { rotateError = null },
            title = { Text("Beberapa foto gagal diputar") },
            text = { Text(rotateError ?: "") },
            confirmButton = { TextButton(onClick = { rotateError = null }) { Text("Tutup") } },
        )
    }
}

@Composable private fun DrawerItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) = NavigationDrawerItem(label = { Text(label) }, selected = selected, onClick = onClick, icon = { Icon(icon, null) }, modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding))
@Composable private fun OverflowItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) = DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)

@Composable private fun SortDialog(current: SortMode, onDismiss: () -> Unit, onSelect: (SortMode) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Urutkan") },
        text = {
            Column {
                SortOption("Nama", SortMode.NAME, current, onSelect, showSubmenu = true)
                SortOption("Tanggal", SortMode.DATE, current, onSelect, showSubmenu = true)
                SortOption("Alur", SortMode.FLOW, current, onSelect)
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun NameSortDialog(current: SortDirection, onSelect: (SortDirection) -> Unit) {
    AlertDialog(
        onDismissRequest = { onSelect(current) },
        title = { Text("Urutkan berdasarkan nama") },
        text = {
            Column {
                DirectionOption("A → Z", SortDirection.ASCENDING, current, onSelect)
                DirectionOption("Z → A", SortDirection.DESCENDING, current, onSelect)
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun DateSortDialog(current: SortDirection, onSelect: (SortDirection) -> Unit) {
    AlertDialog(
        onDismissRequest = { onSelect(current) },
        title = { Text("Urutkan berdasarkan tanggal") },
        text = {
            Column {
                DirectionOption("Terbaru → Terlama", SortDirection.DESCENDING, current, onSelect)
                DirectionOption("Terlama → Terbaru", SortDirection.ASCENDING, current, onSelect)
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun SortOption(label: String, mode: SortMode, current: SortMode, onSelect: (SortMode) -> Unit, showSubmenu: Boolean = false) = Row(Modifier.fillMaxWidth().clickable { onSelect(mode) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
    RadioButton(mode == current, { onSelect(mode) })
    Text(label, Modifier.weight(1f))
    if (showSubmenu) Icon(Icons.Default.ChevronRight, "Pilihan urutan tanggal")
}

@Composable
private fun DirectionOption(label: String, direction: SortDirection, current: SortDirection, onSelect: (SortDirection) -> Unit) = Row(Modifier.fillMaxWidth().clickable { onSelect(direction) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
    RadioButton(direction == current, { onSelect(direction) })
    Text(label)
}

@Composable
private fun RotationDialog(onSelect: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = { onSelect(0) },
        title = { Text("Putar") },
        text = {
            Column {
                RotationOption("Putar ke kiri", -90, onSelect)
                RotationOption("Putar ke kanan", 90, onSelect)
                RotationOption("Putar 180 derajat", 180, onSelect)
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun RotationOption(label: String, degrees: Int, onSelect: (Int) -> Unit) = Row(Modifier.fillMaxWidth().clickable { onSelect(degrees) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
    val icon = when (degrees) {
        -90 -> Icons.Default.RotateLeft
        90 -> Icons.Default.RotateRight
        else -> Icons.Default.RotateRight
    }
    Icon(icon, null)
    Spacer(Modifier.width(12.dp))
    Text(label)
}

@Composable private fun HomeTab.icon() = when (this) { HomeTab.Folders -> Icons.Default.Folder; HomeTab.Photos -> Icons.Default.Image; HomeTab.Videos -> Icons.Default.Movie }
private fun HomeTab.label() = when (this) { HomeTab.Folders -> "Folder"; HomeTab.Photos -> "Foto"; HomeTab.Videos -> "Video" }

@Composable

private fun FolderGrid(
    folders: List<com.example.quickpic.data.MediaFolder>,
    rotationDegrees: (Uri) -> Int = { 0 },
    rotationRevision: Long = 0L,
    onFolderClick: (com.example.quickpic.data.MediaFolder) -> Unit,
) {
    if (folders.isEmpty()) { EmptyState("Tidak ada folder foto/video."); return }

    val gridState = rememberLazyGridState()
    val context = LocalContext.current
    val cache = remember { ThumbnailCache.getInstance(context) }

    // Prefetch thumbnails for the next two rows ahead of the visible items
    LaunchedEffect(gridState.firstVisibleItemIndex, rotationRevision) {
        val visibleItemsInfo = gridState.layoutInfo.visibleItemsInfo
        if (visibleItemsInfo.isEmpty()) return@LaunchedEffect
        val columns = (visibleItemsInfo.maxOf { it.column } + 1).coerceAtLeast(1)
        val lastVisibleIndex = visibleItemsInfo.last().index
        val prefetchCount = columns * 2 // 2 baris ke depan
        val start = lastVisibleIndex + 1
        withContext(Dispatchers.IO) {
            for (i in start until (start + prefetchCount)) {
                if (i >= folders.size) break
                val folder = folders[i]
                if (folder.thumbnail != Uri.EMPTY) {
                    // Warm up memory cache; actual loading is performed by Coil when needed
                    cache.getBitmap(folder.thumbnail, "1:folder:${folder.path}:${folder.thumbnail}:$rotationRevision")
                }
            }
        }
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(folders, key = { it.path }) { folder ->
            FolderCard(folder, rotationDegrees(folder.thumbnail), rotationRevision, onFolderClick)
        }
    }
}
@Composable
private fun FolderCard(
    folder: com.example.quickpic.data.MediaFolder,
    rotationDegrees: Int = 0,
    rotationRevision: Long = 0L,
    onFolderClick: (com.example.quickpic.data.MediaFolder) -> Unit,
) = Column(
    Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .clickable { onFolderClick(folder) }
        .semantics { contentDescription = "Folder ${folder.displayName}" }
) {
    if (folder.thumbnail != Uri.EMPTY) {
        MediaThumbnailImage(folder.thumbnail, null, Modifier.fillMaxWidth().height(120.dp), rotationDegrees, "1:folder:${folder.path}:${folder.thumbnail}:$rotationRevision", rotationRevision, targetSizeDp = 120.dp)
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Folder, null, Modifier.size(20.dp))
        Spacer(Modifier.width(7.dp))
        Column(Modifier.weight(1f)) {
            Text(folder.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Text(
                if (folder.totalCount == 0) "Kosong" else "${folder.totalCount} item",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable internal fun MediaGrid(
    media: List<com.example.quickpic.data.MediaItem>,
    modifier: Modifier = Modifier,
    rotationDegrees: (Uri) -> Int = { 0 },
    rotationRevision: Long = 0L,
    selectionMode: Boolean = false,
    selectedMediaIds: Set<Long> = emptySet(),
    onMediaClick: (Int) -> Unit,
) {
    if (media.isEmpty()) { EmptyState("Tidak ada media di sini.", modifier); return }

    val gridState = rememberLazyGridState()
    val context = LocalContext.current
    val thumbnailCache = remember { ThumbnailCache.getInstance(context) }

    // Prefetch thumbnail untuk 2 baris ke depan dari yang sedang terlihat saat scrolling.
    LaunchedEffect(gridState.firstVisibleItemIndex, rotationRevision) {
        val visibleItemsInfo = gridState.layoutInfo.visibleItemsInfo
        if (visibleItemsInfo.isEmpty()) return@LaunchedEffect
        val columns = (visibleItemsInfo.maxOf { it.column } + 1).coerceAtLeast(1)
        val lastVisibleIndex = visibleItemsInfo.last().index
        val prefetchCount = columns * 2 // 2 baris ke depan
        val start = lastVisibleIndex + 1
        withContext(Dispatchers.IO) {
            for (i in start until (start + prefetchCount)) {
                if (i >= media.size) break
                val item = media[i]
                // Menghangatkan memory/disk cache; pemuatan aktual tetap dilakukan Coil saat item benar-benar tampil.
                thumbnailCache.getBitmap(item.uri, "1:${item.id}:${item.sizeBytes}:${item.dateModifiedSeconds}:$rotationRevision")
            }
        }
    }

    LazyVerticalGrid(state = gridState, columns = GridCells.Adaptive(120.dp), modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(media, key = { it.id }) { item ->
            MediaThumbnail(
                item = item,
                rotationDegrees = rotationDegrees(item.uri),
                rotationRevision = rotationRevision,
                selectionMode = selectionMode,
                selected = item.id in selectedMediaIds,
            ) { onMediaClick(media.indexOf(item)) }
        }
    }
}
@Composable private fun MediaThumbnail(
    item: com.example.quickpic.data.MediaItem,
    rotationDegrees: Int = 0,
    rotationRevision: Long = 0L,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit,
) = Box(
    Modifier
        .fillMaxWidth()
        .height(140.dp)
        .semantics { contentDescription = item.displayName }
        .clickable(onClick = onClick)
) {
    MediaThumbnailImage(item.uri, item.displayName, Modifier.fillMaxSize(), rotationDegrees, "1:${item.id}:${item.sizeBytes}:${item.dateModifiedSeconds}:$rotationRevision", rotationRevision)
    if (item.isVideo) {
        Surface(Modifier.align(Alignment.TopStart), color = MaterialTheme.colorScheme.scrim.copy(alpha = .7f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Movie, null, Modifier.size(16.dp), tint = Color.White)
                Text(" VIDEO", Modifier.padding(end = 6.dp, top = 3.dp, bottom = 3.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (!selectionMode) Icon(Icons.Default.PlayArrow, "Putar video", Modifier.align(Alignment.Center).size(48.dp), tint = Color.White)
    }
    Text(item.displayName, Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(MaterialTheme.colorScheme.scrim.copy(alpha = .7f)).padding(6.dp, 3.dp), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
    if (selectionMode) {
        Surface(
            modifier = Modifier.align(Alignment.TopEnd).padding(7.dp).size(28.dp),
            shape = RoundedCornerShape(50),
            color = if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = .55f),
            tonalElevation = 2.dp,
        ) {
            Icon(
                imageVector = if (selected) Icons.Default.Check else Icons.Default.RadioButtonUnchecked,
                contentDescription = if (selected) "Ditandai" else "Belum ditandai",
                tint = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White,
                modifier = Modifier.padding(4.dp),
            )
        }
    }
}
// Plafon ukuran decode thumbnail dalam px, terlepas dari seberapa tinggi densitas layar
// perangkat. Nilai ini adalah baseline yang sudah terbukti lancar sebelumnya.
private const val MAX_THUMBNAIL_DECODE_PX = 320

@Composable
private fun MediaThumbnailImage(
    uri: Uri,
    contentDescription: String?,
    modifier: Modifier,
    rotationDegrees: Int = 0,
    cacheVersion: String = "1",
    rotationRevision: Long = 0L,
    targetSizeDp: Dp = 140.dp,
) {
    val context = LocalContext.current.applicationContext
    val imageLoader = remember(context) { context.imageLoader }
    val thumbnailCache = remember(context) { com.example.quickpic.ThumbnailCache.getInstance(context) }
    val cachedFile = remember(uri, cacheVersion, rotationRevision) { thumbnailCache.existing(uri, cacheVersion) }
    val saveScope = rememberCoroutineScope()
    val density = LocalDensity.current
    // Target decode di-px sesuai kerapatan layar perangkat (mdpi/hdpi/xhdpi/dst), bukan
    // angka tetap: layar berdensitas rendah tidak boros decode berlebih. Tapi dibatasi
    // MAX_THUMBNAIL_DECODE_PX sebagai plafon: di device densitas tinggi (mis. OnePlus 5T,
    // ~401ppi/density 3x) hasil kali dp*density tanpa batas bisa jauh melebihi ukuran aman
    // sebelumnya (420px vs 320px), yang justru bikin decode makin berat dan grid lag saat
    // scroll cepat—apalagi untuk thumbnail video yang framenya lebih mahal di-decode.
    val targetPx = remember(density, targetSizeDp) {
        with(density) { targetSizeDp.roundToPx() }.coerceIn(1, MAX_THUMBNAIL_DECODE_PX)
    }
    val request = remember(uri, cacheVersion, rotationRevision, targetPx) {
        ImageRequest.Builder(context)
            .data(uri)
            // Grid cells are small; decoding to a bounded thumbnail avoids
            // allocating full-resolution camera images/video frames.
            .size(targetPx, targetPx)
            .memoryCacheKey("quickpic-thumb:$uri:$cacheVersion:$rotationRevision:$targetPx")
            .diskCacheKey("quickpic-source:$uri:$cacheVersion:$rotationRevision:$targetPx")
            .build()
    }

    AsyncImage(
        model = cachedFile ?: request,
        contentDescription = contentDescription,
        imageLoader = imageLoader,
        contentScale = ContentScale.Crop,
        onSuccess = { state ->
            if (cachedFile == null) {
                val bitmap = state.result.drawable.toBitmap()
                saveScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    thumbnailCache.putBitmap(uri, cacheVersion, bitmap)
                }
            }
        },
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .graphicsLayer { rotationZ = rotationDegrees.toFloat() },
    )
}

@Composable private fun MediaViewer(
    items: List<com.example.quickpic.data.MediaItem>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    onRotateRequest: (com.example.quickpic.data.MediaItem) -> Unit,
    onDetailsRequest: () -> Unit,
    onRenameRequest: () -> Unit,
    onMoveRequest: () -> Unit,
    onCropRequest: (com.example.quickpic.data.MediaItem) -> Unit,
    rotationDegrees: (Uri) -> Int,
    rotationRevision: Long = 0L,
) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(0, items.lastIndex), pageCount = { items.size })
    var controlsVisible by remember { mutableStateOf(true) }
    val activity = context.findActivity()
    val scope = rememberCoroutineScope()
    var landscape by rememberSaveable { mutableStateOf(false) }
    var photoZoomInSignal by remember { mutableIntStateOf(0) }
    var photoZoomOutSignal by remember { mutableIntStateOf(0) }
    var viewerOverflowOpen by remember { mutableStateOf(false) }
    BackHandler(onBack = onDismiss)

    Dialog(onDismissRequest = { onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        DisposableEffect(Unit) {
        val window = (context.findActivity())?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()); activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), userScrollEnabled = true) { page ->
                val item = items[page]
                if (item.isVideo) {
                    Box(Modifier.fillMaxSize()) {
                        VideoPlayer(item.uri, Modifier.fillMaxSize())
                        if (controlsVisible) {
                            IconButton(
                                onClick = { if (page > 0) scope.launch { pagerState.animateScrollToPage(page - 1) } },
                                modifier = Modifier.align(Alignment.CenterStart),
                                enabled = page > 0,
                            ) { Icon(Icons.Default.SkipPrevious, "Sebelumnya", tint = Color.White, modifier = Modifier.size(42.dp)) }
                            IconButton(
                                onClick = { if (page < items.lastIndex) scope.launch { pagerState.animateScrollToPage(page + 1) } },
                                modifier = Modifier.align(Alignment.CenterEnd),
                                enabled = page < items.lastIndex,
                            ) { Icon(Icons.Default.SkipNext, "Berikutnya", tint = Color.White, modifier = Modifier.size(42.dp)) }
                        }
                        if (controlsVisible) Text("${page + 1} / ${items.size}", Modifier.align(Alignment.BottomCenter).padding(bottom = 76.dp), color = Color.White)
                    }
                } else {
                    ZoomablePhoto(
                        uri = item.uri,
                        zoomInSignal = photoZoomInSignal,
                        zoomOutSignal = photoZoomOutSignal,
                        rotationDegrees = rotationDegrees(item.uri),
                        rotationRevision = rotationRevision,
                    ) { controlsVisible = !controlsVisible }
                }
            }
            if (controlsVisible) {
                Surface(Modifier.fillMaxWidth().align(Alignment.TopCenter), color = Color.Black.copy(alpha = .55f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.statusBarsPadding().padding(horizontal = 6.dp, vertical = 4.dp)) {
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.ArrowBack, "Kembali", tint = Color.White) }
                        Text(items[pagerState.currentPage].displayName, Modifier.weight(1f), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (items[pagerState.currentPage].isVideo) IconButton(onClick = { landscape = !landscape; activity?.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }) { Icon(Icons.Default.ScreenRotation, "Rotasi", tint = Color.White) }
                        else {
                            IconButton(onClick = { context.shareMedia(items[pagerState.currentPage].uri) }) { Icon(Icons.Default.Share, "Bagikan", tint = Color.White) }
                        }
                        Box {
                            IconButton(onClick = { viewerOverflowOpen = true }) { Icon(Icons.Default.MoreVert, "Menu", tint = Color.White) }
                            DropdownMenu(expanded = viewerOverflowOpen, onDismissRequest = { viewerOverflowOpen = false }) {
                                DropdownMenuItem(text = { Text("Salin") }, onClick = { viewerOverflowOpen = false })
                                DropdownMenuItem(text = { Text("Rincian") }, onClick = { viewerOverflowOpen = false; onDetailsRequest() })
                                DropdownMenuItem(
                                    text = { Text("Putar") },
                                    trailingIcon = { Icon(Icons.Default.ChevronRight, "Submenu") },
                                    onClick = {
                                        viewerOverflowOpen = false
                                        onRotateRequest(items[pagerState.currentPage])
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Ubah") },
                                    enabled = !items[pagerState.currentPage].isVideo,
                                    onClick = {
                                        viewerOverflowOpen = false
                                        onCropRequest(items[pagerState.currentPage])
                                    },
                                )
                                DropdownMenuItem(text = { Text("Gunakan sebagai") }, onClick = { viewerOverflowOpen = false })
                                DropdownMenuItem(text = { Text("Pindah ke") }, onClick = { viewerOverflowOpen = false; onMoveRequest() })
                                DropdownMenuItem(text = { Text("Salin ke") }, onClick = { viewerOverflowOpen = false })
                                DropdownMenuItem(text = { Text("Ganti nama") }, onClick = { viewerOverflowOpen = false; onRenameRequest() })
                                DropdownMenuItem(text = { Text("Lihat di peta") }, onClick = { viewerOverflowOpen = false })
                                DropdownMenuItem(text = { Text("Pengaturan") }, onClick = { viewerOverflowOpen = false })
                            }
                        }
                    }
                }
                if (!items[pagerState.currentPage].isVideo) {
                    Row(Modifier.align(Alignment.TopEnd).padding(top = 68.dp, end = 8.dp)) { ZoomButton(Icons.Default.Remove, "Zoom out") { photoZoomOutSignal++ }; Spacer(Modifier.width(4.dp)); ZoomButton(Icons.Default.Add, "Zoom in") { photoZoomInSignal++ } }
                    Text("${pagerState.currentPage + 1} / ${items.size}", Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp), color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun ZoomButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) = Surface(shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = .6f)) { IconButton(onClick = onClick) { Icon(icon, description, tint = Color.White) } }

@Composable
private fun ZoomablePhoto(
    uri: Uri,
    zoomInSignal: Int,
    zoomOutSignal: Int,
    rotationDegrees: Int,
    rotationRevision: Long = 0L,
    onTap: () -> Unit,
) {
    val context = LocalContext.current
    var scale by remember(uri, rotationRevision) { mutableFloatStateOf(1f) }
    var offset by remember(uri, rotationRevision) { mutableStateOf(Offset.Zero) }
    var lastZoomInSignal by remember(uri) { mutableIntStateOf(zoomInSignal) }
    var lastZoomOutSignal by remember(uri) { mutableIntStateOf(zoomOutSignal) }
    LaunchedEffect(zoomInSignal) {
        if (zoomInSignal > lastZoomInSignal) { scale = min(5f, scale * 1.25f); lastZoomInSignal = zoomInSignal }
    }
    LaunchedEffect(zoomOutSignal) {
        if (zoomOutSignal > lastZoomOutSignal) { scale = max(1f, scale / 1.25f); if (scale <= 1f) offset = Offset.Zero; lastZoomOutSignal = zoomOutSignal }
    }
    val photoRequest = remember(uri, rotationRevision) {
        ImageRequest.Builder(context)
            .data(uri)
            .memoryCacheKey("full-photo:$uri:$rotationRevision")
            .diskCacheKey("full-photo:$uri:$rotationRevision")
            .build()
    }
    Box(Modifier.fillMaxSize().pointerInput(uri, rotationRevision) {
        awaitEachGesture {
            do {
                val event = awaitPointerEvent()
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()
                val isMultiTouch = event.changes.size > 1
                // Hanya tangani gesture ini sendiri (dan cegah pager ikut geser) saat:
                // - ada dua jari (pinch-to-zoom), atau
                // - foto sedang dalam kondisi zoom in (scale > 1) sehingga geser dipakai untuk pan.
                // Selain itu (satu jari, tidak sedang zoom), event diteruskan ke HorizontalPager.
                if (isMultiTouch || scale > 1f) {
                    scale = min(5f, max(1f, scale * zoomChange))
                    offset += panChange
                    event.changes.forEach { change -> if (change.positionChanged()) change.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }) {
        AsyncImage(
            model = photoRequest,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
                rotationZ = rotationDegrees.toFloat()
            }.clickable { onTap() }
        )
    }
}

@Composable
private fun MediaDetailsDialog(
    item: com.example.quickpic.data.MediaItem,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rincian") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailRow("Nama", item.displayName)
                DetailRow("Jenis", item.mimeType.ifBlank { if (item.isVideo) "Video" else "Foto" })
                DetailRow("Lokasi", item.relativePath.ifBlank { "Internal storage" })
                DetailRow("Ukuran", formatFileSize(item.sizeBytes))
                if (item.isVideo) DetailRow("Durasi", formatDuration(item.durationMillis))
                DetailRow("Tanggal", formatDateTime(item.effectiveDateSeconds))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup") } },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "Tidak diketahui"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) { value /= 1024.0; index++ }
    return if (index == 0) "${bytes} ${units[index]}" else String.format(java.util.Locale.US, "%.2f %s", value, units[index])
}

private fun formatDateTime(seconds: Long): String {
    if (seconds <= 0L) return "Tidak diketahui"
    return java.text.SimpleDateFormat("dd MMM yyyy, HH:mm:ss", java.util.Locale("id", "ID"))
        .format(java.util.Date(seconds * 1000L))
}

@Composable
private fun VideoPlayer(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember(uri) { ExoPlayer.Builder(context).build().apply { setMediaItem(ExoMediaItem.fromUri(uri)); prepare(); playWhenReady = false } }
    var position by remember(uri) { mutableLongStateOf(0L) }
    var duration by remember(uri) { mutableLongStateOf(0L) }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.coerceAtLeast(0L)
            delay(500)
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    Box(modifier) {
        AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = true; controllerShowTimeoutMs = 2500 } }, modifier = Modifier.fillMaxSize())
        Text(
            text = "${formatDuration(position)} / ${formatDuration(duration)}",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 52.dp).background(Color.Black.copy(alpha = .55f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val seconds = totalSeconds % 60
    val minutes = totalSeconds / 60 % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

private fun Context.renameMediaItem(item: com.example.quickpic.data.MediaItem, requestedName: String): String? {
    val trimmed = requestedName.trim()
    if (trimmed.isBlank()) return "Nama tidak boleh kosong."
    if (trimmed.contains("/") || trimmed.contains("\\")) return "Nama tidak boleh mengandung karakter /."
    if (trimmed == item.displayName) return null
    return runCatching {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, trimmed) }
        val updated = contentResolver.update(item.uri, values, null, null)
        if (updated != 1) "File tidak dapat diganti nama." else null
    }.getOrElse { "Gagal mengganti nama: ${it.message ?: "akses ditolak"}" }
}

private fun Context.renameMediaFolder(folder: com.example.quickpic.data.MediaFolder, requestedName: String): String? {
    val trimmed = requestedName.trim()
    if (trimmed.isBlank()) return "Nama folder tidak boleh kosong."
    if (trimmed.contains("/") || trimmed.contains("\\")) return "Nama folder tidak boleh mengandung karakter /."
    val parent = folder.path.trimEnd('/').substringBeforeLast('/', missingDelimiterValue = "")
    val newPath = if (parent.isBlank()) "$trimmed/" else "$parent/$trimmed/"
    if (newPath.equals(folder.path, ignoreCase = true)) return null
    return runCatching {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val items = contentResolver.query(
            collection,
            arrayOf(MediaStore.Files.FileColumns._ID),
            "${MediaStore.Files.FileColumns.RELATIVE_PATH}=?",
            arrayOf(folder.path),
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            buildList { while (cursor.moveToNext()) add(cursor.getLong(idColumn)) }
        } ?: emptyList()
        if (items.isEmpty()) return@runCatching "Folder kosong atau sudah berubah."
        val values = ContentValues().apply { put(MediaStore.Files.FileColumns.RELATIVE_PATH, newPath) }
        var changed = 0
        items.forEach { id ->
            val uri = ContentUris.withAppendedId(collection, id)
            changed += contentResolver.update(uri, values, null, null)
        }
        if (changed != items.size) "Sebagian isi folder tidak dapat dipindahkan. Folder belum diganti nama sepenuhnya." else null
    }.getOrElse { "Gagal mengganti nama folder: ${it.message ?: "akses ditolak"}" }
}

@Composable
private fun RenameDialog(
    title: String,
    initialName: String,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Nama baru") },
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { { Text(it) } },
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
        confirmButton = { TextButton(onClick = { onRename(name) }, enabled = name.trim().isNotEmpty()) { Text("Simpan") } },
    )
}

private fun Context.shareMedia(uri: Uri) = shareMedia(listOf(uri))

private fun Context.shareMedia(uris: List<Uri>) {
    if (uris.isEmpty()) return
    runCatching {
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }.apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        startActivity(Intent.createChooser(intent, "Bagikan media"))
    }
}

private data class CopyResult(val copied: Int, val failed: Int, val errors: List<String> = emptyList())

private fun CopyResult.failureMessage(action: String = "disalin"): String = buildString {
    append(
        if (copied > 0) {
            "$copied file berhasil $action, $failed gagal."
        } else {
            "$failed file gagal $action."
        },
    )
    if (errors.isNotEmpty()) {
        append("\n\nDetail teknis:")
        errors.take(3).forEach { error ->
            append("\n• ")
            append(error)
        }
        if (errors.size > 3) append("\n• ${errors.size - 3} kegagalan lainnya.")
    }
}

private fun isMediaStoreInsertAllowed(destinationPath: String, isVideo: Boolean): Boolean {
    val primaryDir = destinationPath.trim().trimStart('/').substringBefore('/').lowercase()
    return if (isVideo) {
        primaryDir == "dcim" || primaryDir == "movies"
    } else {
        primaryDir == "dcim" || primaryDir == "pictures"
    }
}

private fun Context.copyMediaItems(
    items: List<com.example.quickpic.data.MediaItem>,
    destinationPath: String,
): CopyResult {
    var copied = 0
    var failed = 0
    val errors = mutableListOf<String>()
    items.distinctBy { it.id }.forEach { item ->
        val mimeType = item.mimeType.ifBlank { if (item.isVideo) "video/mp4" else "image/jpeg" }
        var targetUri: Uri? = null
        var createdFile: File? = null
        var stage = "insert"
        try {
            if (isMediaStoreInsertAllowed(destinationPath, item.isVideo)) {
                val mediaCollection = if (item.isVideo) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, item.displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, destinationPath)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }

                // A video in Pictures/Screenshots is still a video.  Insert it in
                // MediaStore.Video, which officially indexes video in Pictures/.
                // Do not fall back to MediaStore.Files: it is an aggregation view,
                // and its failure used to hide the actual error from Video/Images.
                targetUri = contentResolver.insert(mediaCollection, values)
                    ?: throw IllegalStateException("MediaStore tidak dapat membuat file tujuan")

                stage = "read"
                val bytesWritten = contentResolver.openInputStream(item.uri)?.use { input ->
                    stage = "write"
                    contentResolver.openOutputStream(targetUri)?.use { output ->
                        input.copyTo(output, bufferSize = 1024 * 1024)
                    } ?: throw IllegalStateException("Tidak dapat membuka file tujuan")
                } ?: throw IllegalStateException("Tidak dapat membaca file sumber")

                stage = "publish"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val updated = contentResolver.update(
                        targetUri,
                        ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                        null,
                        null,
                    )
                    if (updated != 1) throw IllegalStateException("Tidak dapat mempublikasikan file tujuan")
                }

                stage = "verify"
                verifyMediaStoreTarget(
                    targetUri = targetUri,
                    expectedDestinationPath = destinationPath,
                    expectedDisplayName = item.displayName,
                    expectedBytes = bytesWritten,
                )
                copied++
            } else {
                // For non-standard MediaStore directories (such as WhatsApp, Download, etc.),
                // copy directly via filesystem and index using MediaScanner.
                stage = "prepare_dir"
                val destDir = File(Environment.getExternalStorageDirectory(), normalizeRelativePath(destinationPath).trimEnd('/'))
                if (!destDir.exists()) {
                    destDir.mkdirs()
                }
                val targetFile = File(destDir, item.displayName)
                createdFile = targetFile

                stage = "read"
                val bytesWritten = contentResolver.openInputStream(item.uri)?.use { input ->
                    stage = "write"
                    targetFile.outputStream().use { output ->
                        input.copyTo(output, bufferSize = 1024 * 1024)
                    }
                } ?: throw IllegalStateException("Tidak dapat membaca file sumber")

                stage = "verify"
                if (targetFile.length() != bytesWritten) {
                    throw IllegalStateException("Ukuran tujuan ${targetFile.length()} byte, seharusnya $bytesWritten byte")
                }

                stage = "publish"
                MediaScannerConnection.scanFile(
                    this,
                    arrayOf(targetFile.absolutePath),
                    arrayOf(mimeType),
                    null,
                )
                copied++
            }
        } catch (error: Throwable) {
            failed++
            targetUri?.let { runCatching { contentResolver.delete(it, null, null) } }
            createdFile?.let { runCatching { if (it.exists()) it.delete() } }
            errors += "${item.displayName}: $stage: ${error.javaClass.simpleName}: ${error.message ?: "tanpa pesan"}"
        }
    }
    return CopyResult(copied, failed, errors)
}

/**
 * Copies media through the Storage Access Framework. This is required on
 * providers that expose Pictures/ to the gallery but reject creating a new
 * video there through MediaStore.Video.
 */
private fun Context.copyMediaItemsToTree(
    items: List<com.example.quickpic.data.MediaItem>,
    treeUri: Uri,
    expectedDestinationPath: String? = null,
): CopyResult {
    var copied = 0
    var failed = 0
    val errors = mutableListOf<String>()
    val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
    val expectedTreeDocumentId = expectedDestinationPath?.let(::externalStorageDocumentId)
    if (expectedTreeDocumentId != null && !treeDocumentId.equals(expectedTreeDocumentId, ignoreCase = true)) {
        return CopyResult(
            copied = 0,
            failed = items.distinctBy { it.id }.size,
            errors = items.distinctBy { it.id }.map { item ->
                "${item.displayName}: folder: folder yang dipilih ($treeDocumentId) bukan $expectedTreeDocumentId"
            },
        )
    }
    val treeDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        treeDocumentId,
    )

    items.distinctBy { it.id }.forEach { item ->
        var targetUri: Uri? = null
        var stage = "insert"
        try {
            val mimeType = item.mimeType.ifBlank { if (item.isVideo) "video/mp4" else "image/jpeg" }
            targetUri = DocumentsContract.createDocument(
                contentResolver,
                treeDocumentUri,
                mimeType,
                item.displayName,
            ) ?: throw IllegalStateException("Pemilih folder tidak dapat membuat file tujuan")

            stage = "read"
            val bytesWritten = contentResolver.openInputStream(item.uri)?.use { input ->
                stage = "write"
                contentResolver.openOutputStream(targetUri, "w")?.use { output ->
                    input.copyTo(output, bufferSize = 1024 * 1024)
                } ?: throw IllegalStateException("Tidak dapat membuka file tujuan")
            } ?: throw IllegalStateException("Tidak dapat membaca file sumber")

            stage = "verify"
            verifyTreeTarget(
                targetUri = targetUri,
                expectedTreeDocumentId = treeDocumentId,
                expectedDestinationPath = expectedDestinationPath,
                expectedDisplayName = item.displayName,
                expectedBytes = bytesWritten,
            )
            scanTreeDocument(targetUri, mimeType)
            copied++
        } catch (error: Throwable) {
            failed++
            targetUri?.let { runCatching { DocumentsContract.deleteDocument(contentResolver, it) } }
            errors += "${item.displayName}: $stage: ${error.javaClass.simpleName}: ${error.message ?: "tanpa pesan"}"
        }
    }
    return CopyResult(copied, failed, errors)
}

/**
 * A source must never be deleted just because a write call returned normally.
 * Some document providers buffer writes, so reopen the target and compare its
 * contents before the move flow is allowed to remove the source.
 */
private fun Context.verifyMediaStoreTarget(
    targetUri: Uri,
    expectedDestinationPath: String,
    expectedDisplayName: String,
    expectedBytes: Long,
) {
    val expectedPath = normalizeRelativePath(expectedDestinationPath)
    val metadata = contentResolver.query(
        targetUri,
        arrayOf(
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
        ),
        null,
        null,
        null,
    )?.use { cursor ->
        if (!cursor.moveToFirst()) throw IllegalStateException("File tujuan tidak ditemukan setelah dibuat")
        Triple(
            cursor.getString(0).orEmpty(),
            cursor.getString(1).orEmpty(),
            if (cursor.isNull(2)) null else cursor.getLong(2),
        )
    } ?: throw IllegalStateException("File tujuan tidak dapat diperiksa")

    if (!metadata.first.equals(expectedPath, ignoreCase = true)) {
        throw IllegalStateException("Lokasi tujuan berubah: ${metadata.first.ifBlank { "(kosong)" }}")
    }
    if (metadata.second != expectedDisplayName) {
        throw IllegalStateException("Nama tujuan berubah: ${metadata.second.ifBlank { "(kosong)" }}")
    }
    verifyTargetBytes(targetUri, expectedBytes, metadata.third)
}

private fun Context.verifyTreeTarget(
    targetUri: Uri,
    expectedTreeDocumentId: String,
    expectedDestinationPath: String?,
    expectedDisplayName: String,
    expectedBytes: Long,
) {
    val documentId = DocumentsContract.getDocumentId(targetUri)
    val parentDocumentId = documentId.substringBeforeLast('/', missingDelimiterValue = "")
    if (!parentDocumentId.equals(expectedTreeDocumentId, ignoreCase = true)) {
        throw IllegalStateException("Lokasi tujuan berbeda dari folder yang dipilih: $documentId")
    }
    expectedDestinationPath?.let { destinationPath ->
        val expectedDocumentId = externalStorageDocumentId(destinationPath)
        if (!parentDocumentId.equals(expectedDocumentId, ignoreCase = true)) {
            throw IllegalStateException("Lokasi tujuan bukan $expectedDocumentId")
        }
    }

    val displayName = contentResolver.query(
        targetUri,
        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (!cursor.moveToFirst()) throw IllegalStateException("File tujuan tidak ditemukan setelah dibuat")
        cursor.getString(0).orEmpty()
    } ?: throw IllegalStateException("File tujuan tidak dapat diperiksa")
    if (displayName != expectedDisplayName) {
        throw IllegalStateException("Nama tujuan berubah: ${displayName.ifBlank { "(kosong)" }}")
    }
    verifyTargetBytes(targetUri, expectedBytes)
}

private fun Context.verifyTargetBytes(targetUri: Uri, expectedBytes: Long, reportedSize: Long? = null) {
    if (reportedSize != null && reportedSize != expectedBytes) {
        throw IllegalStateException("Ukuran metadata tujuan $reportedSize byte, seharusnya $expectedBytes byte")
    }
    val verifiedBytes = contentResolver.openInputStream(targetUri)?.use(InputStream::countBytes)
        ?: throw IllegalStateException("File tujuan tidak dapat dibuka ulang")
    if (verifiedBytes != expectedBytes) {
        throw IllegalStateException("Ukuran tujuan $verifiedBytes byte, seharusnya $expectedBytes byte")
    }
}

private fun InputStream.countBytes(): Long {
    val buffer = ByteArray(1024 * 1024)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) return total
        total += read
    }
}

private fun normalizeRelativePath(path: String): String = "${path.trim().trim('/').trimEnd('/')}/"

private fun externalStorageDocumentId(path: String): String = "primary:${normalizeRelativePath(path).trimEnd('/')}"

private fun Context.scanTreeDocument(documentUri: Uri, mimeType: String) {
    runCatching {
        val documentId = DocumentsContract.getDocumentId(documentUri)
        val relativePath = documentId.substringAfter(':', documentId).trimStart('/')
        if (relativePath.isNotBlank()) {
            val path = File(Environment.getExternalStorageDirectory(), relativePath).absolutePath
            MediaScannerConnection.scanFile(this, arrayOf(path), arrayOf(mimeType), null)
        }
    }
}

@Composable
private fun CopyDestinationDialog(
    title: String,
    folders: List<com.example.quickpic.data.MediaFolder>,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onNewFolderClick: () -> Unit = {},
    onFolderSelected: (com.example.quickpic.data.MediaFolder) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.78f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (title.contains("Pindah", ignoreCase = true)) {
                            Icons.Default.DriveFileMove
                        } else {
                            Icons.Default.ContentCopy
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "Pilih folder album tujuan",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onNewFolderClick, enabled = enabled) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "Buat folder baru", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = onDismiss, enabled = enabled) {
                        Icon(Icons.Default.Close, contentDescription = "Tutup")
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // Folder List
                if (folders.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Belum ada folder media yang dapat dipilih.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 6.dp),
                    ) {
                        items(folders, key = { it.path }) { folder ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = enabled) { onFolderSelected(folder) }
                                    .padding(horizontal = 18.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // Folder Album Thumbnail Cover
                                Box(
                                    modifier = Modifier
                                        .size(54.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (folder.thumbnail != Uri.EMPTY) {
                                        MediaThumbnailImage(
                                            uri = folder.thumbnail,
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize(),
                                            rotationDegrees = 0,
                                            cacheVersion = "1:folder",
                                            targetSizeDp = 54.dp,
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.Folder,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                            modifier = Modifier.size(28.dp),
                                        )
                                    }
                                }

                                Spacer(Modifier.width(16.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = folder.displayName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = folder.path,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val countText = if (folder.totalCount == 0) {
                                        "0 item (folder kosong)"
                                    } else if (folder.photoCount > 0 && folder.videoCount > 0) {
                                        "${folder.totalCount} item (${folder.photoCount} foto, ${folder.videoCount} video)"
                                    } else if (folder.videoCount > 0) {
                                        "${folder.totalCount} video"
                                    } else {
                                        "${folder.totalCount} foto"
                                    }
                                    Text(
                                        text = countText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (folder.totalCount == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                                    )
                                }

                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 88.dp, end = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // Bottom actions
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${folders.size} folder tersedia",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (!enabled) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.5.dp,
                        )
                    } else {
                        TextButton(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text("Batal", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CopyFailureDialog(title: String = "Salin gagal", message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup") } },
    )
}

private fun Context.deleteMediaItems(
    uris: List<Uri>,
    launcher: androidx.activity.result.ActivityResultLauncher<IntentSenderRequest>?,
    onPermissionRequired: (remainingUris: List<Uri>, sender: android.content.IntentSender) -> Unit,
): Boolean {
    val uniqueUris = uris.distinct()
    if (uniqueUris.isEmpty()) return true

    // Android 11+ dapat meminta satu persetujuan sistem untuk seluruh batch.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        return runCatching {
            val request = MediaStore.createDeleteRequest(contentResolver, uniqueUris)
            launcher?.launch(IntentSenderRequest.Builder(request.intentSender).build()) ?: return false
            false
        }.getOrElse {
            android.widget.Toast.makeText(
                this,
                "File tidak dapat dihapus: ${it.message ?: "akses ditolak"}",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            false
        }
    }

    // Android 10 (API 29) dapat meminta persetujuan satu file melalui
    // RecoverableSecurityException. Proses dilakukan berurutan.
    // PENTING: ketika satu file berhasil dihapus, file tersebut langsung
    // dikeluarkan dari queue. Kalau queue lama diulang dari awal, delete()
    // terhadap URI yang sudah hilang mengembalikan 0 dan menghasilkan pesan
    // palsu "Sebagian file tidak dapat dihapus".
    for (index in uniqueUris.indices) {
        val uri = uniqueUris[index]
        try {
            val deleted = contentResolver.delete(uri, null, null)
            if (deleted <= 0) {
                // A URI can already be gone after a previous approved request.
                // Treat that as success instead of turning the whole batch into
                // the misleading "Sebagian file tidak dapat dihapus" state.
                val stillExists = runCatching {
                    contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.moveToFirst() } == true
                }.getOrDefault(false)
                if (stillExists) {
                    android.widget.Toast.makeText(
                        this,
                        "File tidak dapat dihapus. Pastikan izin penyimpanan diberikan.",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    return false
                }
            }
        } catch (error: Throwable) {
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && error is android.app.RecoverableSecurityException) {
                val remaining = uniqueUris.drop(index)
                if (remaining.isEmpty()) return true
                onPermissionRequired(remaining, error.userAction.actionIntent.intentSender)
                return false
            }
            android.widget.Toast.makeText(
                this,
                "Gagal menghapus file: ${error.message ?: "akses ditolak"}",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            return false
        }
    }

    return true
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable private fun SettingsScreen(onBack: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Pengaturan") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Kembali") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SettingsItem("Umum", Icons.Default.Tune)
            SettingsItem("Jelajah", Icons.Default.FolderOpen)
            SettingsItem("Lihat", Icons.Default.Visibility)
            SettingsItem("Keamanan", Icons.Default.Lock)
            SettingsItem("Singgahan (Cache)", Icons.Default.Cached)
        }
    }
}
@Composable private fun SettingsItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector) = ListItem(headlineContent = { Text(label) }, leadingContent = { Icon(icon, null) }, modifier = Modifier.clickable { })

@Composable private fun EmptyState(message: String, modifier: Modifier = Modifier) = Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(message) }
@Composable private fun PermissionRequired(modifier: Modifier, onRequestPermission: () -> Unit) = Column(modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text("Izinkan akses foto dan video untuk menampilkan galeri."); Button(onClick = onRequestPermission, Modifier.padding(top = 16.dp)) { Text("Izinkan akses") } }
@Composable private fun Loading(modifier: Modifier) = Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
@Composable private fun ErrorMessage(message: String, modifier: Modifier) = Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(message) }

private fun Context.hasMediaPermission(): Boolean = arrayOf(
    Manifest.permission.READ_EXTERNAL_STORAGE,
    Manifest.permission.WRITE_EXTERNAL_STORAGE,
).all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

private fun mediaPermissions(): Array<String> = arrayOf(
    Manifest.permission.READ_EXTERNAL_STORAGE,
    Manifest.permission.WRITE_EXTERNAL_STORAGE,
)
private fun Context.findActivity(): Activity? { var c: Context = this; while (c is android.content.ContextWrapper) { if (c is Activity) return c; c = c.baseContext }; return null }
