package com.deadvault.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.deadvault.app.R
import com.deadvault.app.data.VaultFolder
import com.deadvault.app.data.VaultItem
import kotlinx.coroutines.delay
import java.io.File

// ------------------------------------------------------------------ root

@Composable
fun DeadVaultRoot(vm: VaultViewModel) {
    LaunchedEffect(Unit) {
        while (true) {
            vm.tick()
            delay(1000)
        }
    }
    when (vm.phase) {
        Phase.Loading -> Box(Modifier.fillMaxSize())
        Phase.Setup -> SetupScreen(vm)
        Phase.Locked -> LockScreen(vm)
        Phase.Unlocked -> UnlockedHost(vm)
    }
}

@Composable
private fun UnlockedHost(vm: VaultViewModel) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.message = null
        }
    }

    // One-time ask for notification permission so the pre-deadline warning can be shown.
    val notificationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.importUris(uris)
    }
    val deleteOriginals =
        rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { }
    LaunchedEffect(vm.deleteRequest) {
        vm.deleteRequest?.let { sender ->
            vm.suppressLock()
            vm.deleteRequest = null
            deleteOriginals.launch(IntentSenderRequest.Builder(sender).build())
        }
    }

    BackHandler(enabled = vm.overlay != Overlay.None || vm.selection.isNotEmpty() || vm.currentFolderId != null) {
        when {
            vm.overlay != Overlay.None -> vm.closeOverlay()
            vm.selection.isNotEmpty() -> vm.clearSelection()
            else -> vm.openFolder(null)
        }
    }

    when (val o = vm.overlay) {
        Overlay.None -> GalleryScreen(
            vm = vm,
            snackbar = snackbar,
            onAdd = {
                vm.suppressLock()
                picker.launch(arrayOf("image/*", "video/*"))
            },
        )
        is Overlay.Viewer -> ViewerScreen(vm, o.startIndex)
        Overlay.Settings -> SettingsScreen(vm)
    }
}

// --------------------------------------------------------------- gallery

@Composable
private fun GalleryScreen(vm: VaultViewModel, snackbar: SnackbarHostState, onAdd: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    var showMove by remember { mutableStateOf(false) }

    /** null = dialog hidden, true = the new folder should receive the selected items. */
    var newFolderMovesSelection by remember { mutableStateOf<Boolean?>(null) }
    var folderActions by remember { mutableStateOf<VaultFolder?>(null) }
    var renameTarget by remember { mutableStateOf<VaultFolder?>(null) }
    var deleteFolderTarget by remember { mutableStateOf<VaultFolder?>(null) }

    val selecting = vm.selection.isNotEmpty()
    val folder = vm.currentFolder
    val visible = vm.visibleItems

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (!selecting) {
                FloatingActionButton(onClick = onAdd, containerColor = Mint) {
                    Icon(Icons.Default.Add, "Add photos and videos", tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                selecting -> {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = vm::clearSelection) { Icon(Icons.Default.Close, "Cancel selection") }
                        Text(
                            "${vm.selection.size} selected",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        TextButton(onClick = vm::selectAllVisible) { Text("Select all") }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(onClick = { showMove = true }) { Text("Move to folder") }
                        TextButton(onClick = vm::restoreSelected) { Text("Restore") }
                        TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = Danger) }
                    }
                }
                folder != null -> {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { vm.openFolder(null) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to main library")
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                folder.name,
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${visible.size} ${if (visible.size == 1) "item" else "items"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { folderActions = folder }) { Icon(Icons.Default.MoreVert, "Folder options") }
                        IconButton(onClick = vm::lockNow) { Icon(Icons.Default.Lock, "Lock now") }
                    }
                }
                else -> {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("DeadVault", style = MaterialTheme.typography.titleLarge)
                            val soon = vm.remainingMs < 24L * 60 * 60 * 1000
                            Text(
                                "Erases in ${formatRemaining(vm.remainingMs)} unless you unlock again",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (soon) Amber else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "${vm.items.size} ${if (vm.items.size == 1) "item" else "items"} · " +
                                    "${vm.folders.size} ${if (vm.folders.size == 1) "folder" else "folders"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { newFolderMovesSelection = false }) {
                            Icon(painterResource(R.drawable.ic_folder), "New folder")
                        }
                        IconButton(onClick = vm::openSettings) { Icon(Icons.Default.Settings, "Settings") }
                        IconButton(onClick = vm::lockNow) { Icon(Icons.Default.Lock, "Lock now") }
                    }
                }
            }

            if (vm.busy || vm.working) LinearProgressIndicator(Modifier.fillMaxWidth())

            val nothingToShow = visible.isEmpty() && (folder != null || vm.folders.isEmpty())
            if (nothingToShow) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (folder != null) {
                            "This folder is empty.\nTap + to add photos here, or move items in from the main library."
                        } else {
                            "Your vault is empty.\nTap + to add photos and videos."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (folder == null && !selecting) {
                        items(vm.folders, key = { "folder_" + it.id }) { f ->
                            FolderTile(
                                folder = f,
                                count = vm.folderItemCount(f.id),
                                onClick = { vm.openFolder(f.id) },
                                onLongClick = { folderActions = f },
                            )
                        }
                    }
                    itemsIndexed(visible, key = { _, item -> item.id }) { index, item ->
                        ThumbCell(
                            vm = vm,
                            item = item,
                            selected = item.id in vm.selection,
                            onClick = { if (selecting) vm.toggleSelected(item.id) else vm.openViewer(index) },
                            onLongClick = { vm.toggleSelected(item.id) },
                        )
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${vm.selection.size} permanently?") },
            text = { Text("These items will be erased from the vault. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deleteSelected() }) { Text("Delete", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    if (showMove) {
        AlertDialog(
            onDismissRequest = { showMove = false },
            title = { Text("Move ${vm.selection.size} to…") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (vm.currentFolderId != null) {
                        TextButton(onClick = { showMove = false; vm.moveSelected(null) }) { Text("Main library") }
                    }
                    vm.folders.filter { it.id != vm.currentFolderId }.forEach { f ->
                        TextButton(onClick = { showMove = false; vm.moveSelected(f.id) }) { Text(f.name) }
                    }
                    TextButton(onClick = { showMove = false; newFolderMovesSelection = true }) { Text("+ New folder…") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showMove = false }) { Text("Cancel") } },
        )
    }

    newFolderMovesSelection?.let { moves ->
        NameDialog(
            title = "New folder",
            initial = "",
            confirmLabel = "Create",
            onConfirm = { name ->
                newFolderMovesSelection = null
                vm.createFolder(name, moveSelection = moves)
            },
            onDismiss = { newFolderMovesSelection = null },
        )
    }

    folderActions?.let { f ->
        AlertDialog(
            onDismissRequest = { folderActions = null },
            title = { Text(f.name) },
            text = {
                Column {
                    TextButton(onClick = { renameTarget = f; folderActions = null }) { Text("Rename") }
                    TextButton(onClick = { deleteFolderTarget = f; folderActions = null }) {
                        Text("Delete folder", color = Danger)
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { folderActions = null }) { Text("Close") } },
        )
    }

    renameTarget?.let { f ->
        NameDialog(
            title = "Rename folder",
            initial = f.name,
            confirmLabel = "Rename",
            onConfirm = { name ->
                renameTarget = null
                vm.renameFolder(f.id, name)
            },
            onDismiss = { renameTarget = null },
        )
    }

    deleteFolderTarget?.let { f ->
        AlertDialog(
            onDismissRequest = { deleteFolderTarget = null },
            title = { Text("Delete folder \"${f.name}\"?") },
            text = { Text("The photos and videos inside are not deleted — they move back to the main library.") },
            confirmButton = {
                TextButton(onClick = { deleteFolderTarget = null; vm.deleteFolder(f.id) }) {
                    Text("Delete folder", color = Danger)
                }
            },
            dismissButton = { TextButton(onClick = { deleteFolderTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = { Text("Folder name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderTile(folder: VaultFolder, count: Int, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(R.drawable.ic_folder), null, Modifier.size(40.dp), tint = Mint)
        Spacer(Modifier.height(4.dp))
        Text(
            folder.name,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "$count",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThumbCell(
    vm: VaultViewModel,
    item: VaultItem,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val bmp by produceState<Bitmap?>(null, item.id) { value = vm.thumb(item.id) }
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        bmp?.let {
            Image(it.asImageBitmap(), item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        if (item.isVideo) {
            Icon(
                Icons.Default.PlayArrow, "Video",
                Modifier.align(Alignment.BottomStart).padding(4.dp).size(22.dp),
                tint = Color.White,
            )
        }
        if (selected) {
            Box(
                Modifier.fillMaxSize().background(Color(0x886EE7B7)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Check, "Selected", Modifier.size(32.dp), tint = Color.White)
            }
        }
    }
}

// ---------------------------------------------------------------- viewer

@Composable
private fun ViewerScreen(vm: VaultViewModel, startIndex: Int) {
    val shown = vm.visibleItems
    val count = shown.size
    if (count == 0) {
        LaunchedEffect(Unit) { vm.closeOverlay() }
        return
    }
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, count - 1), pageCount = { vm.visibleItems.size })

    // While a photo is zoomed in, one-finger drags pan the photo instead of swiping to the next one.
    var zoomed by remember { mutableStateOf(false) }
    LaunchedEffect(pager.currentPage) { zoomed = false }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = !zoomed,
        ) { page ->
            vm.visibleItems.getOrNull(page)?.let { item ->
                if (item.isVideo) {
                    VideoPage(vm, item, active = pager.currentPage == page)
                } else {
                    ImagePage(vm, item, active = pager.currentPage == page, onZoomed = { zoomed = it })
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().safeDrawingPadding().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = vm::closeOverlay) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
            Text(
                vm.visibleItems.getOrNull(pager.currentPage)?.name.orEmpty(),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${pager.currentPage + 1} / ${vm.visibleItems.size}",
                color = Color.White,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
    }
}

@Composable
private fun ImagePage(vm: VaultViewModel, item: VaultItem, active: Boolean, onZoomed: (Boolean) -> Unit) {
    val state by produceState(initialValue = false to (null as Bitmap?), item.id) {
        value = true to vm.fullImage(item)
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val (loaded, bmp) = state
        when {
            !loaded -> CircularProgressIndicator()
            bmp == null -> Text("This image can't be displayed.", color = Color.White)
            else -> ZoomableImage(bmp, item.name, active, onZoomed)
        }
    }
}

private const val MAX_ZOOM = 6f
private const val ZOOMED_THRESHOLD = 1.01f

/** Keeps a pan inside the picture: you can't drag the image off the screen. */
private fun clampOffset(o: Offset, scale: Float, width: Float, height: Float): Offset {
    val maxX = (width * (scale - 1f) / 2f).coerceAtLeast(0f)
    val maxY = (height * (scale - 1f) / 2f).coerceAtLeast(0f)
    return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
}

/**
 * Pinch to zoom (up to 6x, around your fingers), drag to pan, double-tap to zoom in / back out.
 * A single finger on an un-zoomed photo is left alone so the pager can still swipe to the next one.
 */
@Composable
private fun ZoomableImage(bitmap: Bitmap, description: String, active: Boolean, onZoomed: (Boolean) -> Unit) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val isZoomed = scale > ZOOMED_THRESHOLD
    LaunchedEffect(isZoomed, active) {
        if (active) onZoomed(isZoomed)
    }

    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = description,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        if (scale > ZOOMED_THRESHOLD) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            val target = 2.5f
                            val center = Offset(size.width / 2f, size.height / 2f)
                            scale = target
                            offset = clampOffset(
                                (center - tap) * (target - 1f),
                                target,
                                size.width.toFloat(),
                                size.height.toFloat(),
                            )
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    // Once a second finger has joined, keep owning the gesture until every finger is up, so
                    // lifting one finger at the end of a pinch can't turn into a swipe to the next photo.
                    var multiTouch = false
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1) multiTouch = true
                        val alreadyConsumed = event.changes.any { it.isConsumed }
                        if (!alreadyConsumed && (multiTouch || scale > ZOOMED_THRESHOLD)) {
                            val zoomChange = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = false)
                            val newScale = (scale * zoomChange).coerceIn(1f, MAX_ZOOM)
                            val ratio = newScale / scale
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val focus = if (centroid.isSpecified) centroid else center
                            val raw = (focus - center) * (1f - ratio) + offset * ratio + pan
                            scale = newScale
                            offset = if (newScale <= ZOOMED_THRESHOLD) {
                                Offset.Zero
                            } else {
                                clampOffset(raw, newScale, size.width.toFloat(), size.height.toFloat())
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (!alreadyConsumed && event.changes.any { it.pressed })
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    )
}

@Composable
private fun VideoPage(vm: VaultViewModel, item: VaultItem, active: Boolean) {
    var playing by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(active) { if (!active) playing = false }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (!playing) {
            val thumb by produceState<Bitmap?>(null, item.id) { value = vm.thumb(item.id) }
            thumb?.let {
                Image(it.asImageBitmap(), item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            IconButton(onClick = { playing = true }, modifier = Modifier.size(88.dp)) {
                Icon(Icons.Default.PlayArrow, "Play video", Modifier.size(72.dp), tint = Color.White)
            }
        } else {
            val state by produceState(initialValue = false to (null as File?), item.id) {
                value = true to vm.videoFile(item)
            }
            val (loaded, file) = state
            when {
                !loaded -> CircularProgressIndicator()
                file == null -> Text("This video can't be played.", color = Color.White)
                else -> {
                    DisposableEffect(file) { onDispose { vm.discardVideoFile(file) } }
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                                setVideoPath(file.absolutePath)
                                setOnPreparedListener { start() }
                            }
                        },
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------- settings

@Composable
private fun SettingsScreen(vm: VaultViewModel) {
    val context = LocalContext.current
    val activity = context.findFragmentActivity()

    var current by rememberSaveable { mutableStateOf("") }
    var newPass by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var passMessage by remember { mutableStateOf<String?>(null) }
    var passIsError by remember { mutableStateOf(false) }

    var showWipe by remember { mutableStateOf(false) }
    var wipePass by rememberSaveable { mutableStateOf("") }
    var wipeWrong by remember { mutableStateOf(false) }

    fun openAllFilesSettings() {
        vm.suppressLock()
        val specific = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        runCatching { context.startActivity(specific) }.onFailure {
            runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::closeOverlay) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }

        SectionTitle("Check-in interval")
        Text(
            "You must unlock the vault at least this often. Changing it restarts the countdown.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IntervalChips(selected = vm.intervalMs, onSelect = vm::changeInterval)
        Text(
            "Next check-in due: ${formatDateTime(System.currentTimeMillis() + vm.remainingMs)}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionTitle("Fingerprint / face unlock")
        SwitchRow("Unlock with biometrics", vm.biometricEnabled) { vm.setBiometric(it, activity) }
        Text(
            "A biometric unlock counts as a check-in. If a new fingerprint or face is added to this " +
                "phone, biometric unlock switches itself off and you'll need your passcode.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionTitle("Originals")
        SwitchRow("Delete originals automatically", vm.autoDelete) {
            vm.changeAutoDelete(it)
            if (it && !vm.hasAllFilesAccess) openAllFilesSettings()
        }
        if (vm.autoDelete && !vm.hasAllFilesAccess) {
            Text(
                "Needs \"All files access\" so Android lets DeadVault delete photos without asking each time. " +
                    "Until you allow it, you'll still be asked to confirm.",
                color = Amber,
            )
            Button(onClick = ::openAllFilesSettings) { Text("Allow all files access") }
        } else if (vm.autoDelete) {
            Text(
                "On. Each original is deleted right after its encrypted copy has been checked. " +
                    "Copies already backed up to Google Photos or another cloud are not touched.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "Off. After adding items, Android will ask you to confirm deleting the originals.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionTitle("Wrong-passcode limit")
        Text(
            "Wipe the vault after this many wrong passcodes in a row.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5 to "5", 10 to "10", 20 to "20", 0 to "Off").forEach { (n, label) ->
                FilterChip(
                    selected = vm.maxAttempts == n,
                    onClick = { vm.changeMaxAttempts(n) },
                    label = { Text(label) },
                )
            }
        }

        SectionTitle("Change passcode")
        PasscodeField("Current passcode", current, { current = it })
        PasscodeField("New passcode (6+ characters)", newPass, { newPass = it })
        PasscodeField("Confirm new passcode", confirm, { confirm = it }, ImeAction.Done)
        passMessage?.let { Text(it, color = if (passIsError) MaterialTheme.colorScheme.error else Mint) }
        Button(
            onClick = {
                vm.changePasscode(current, newPass, confirm) { err ->
                    passIsError = err != null
                    passMessage = err ?: "Passcode changed."
                    if (err == null) {
                        current = ""; newPass = ""; confirm = ""
                    }
                }
            },
            enabled = current.isNotEmpty() && newPass.isNotEmpty(),
        ) { Text("Change passcode") }

        SectionTitle("Danger zone")
        Button(
            onClick = { wipePass = ""; wipeWrong = false; showWipe = true },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) { Text("Wipe vault now") }
    }

    if (showWipe) {
        AlertDialog(
            onDismissRequest = { showWipe = false },
            title = { Text("Wipe everything?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Every photo and video in the vault will be erased permanently. Enter your passcode to confirm.")
                    PasscodeField("Passcode", wipePass, { wipePass = it }, ImeAction.Done)
                    if (wipeWrong) Text("Incorrect passcode.", color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.wipeNow(wipePass) { wipeWrong = true } },
                    enabled = wipePass.isNotEmpty(),
                ) { Text("Wipe", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { showWipe = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = Mint)
}
