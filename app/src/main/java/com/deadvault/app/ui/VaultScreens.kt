package com.deadvault.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
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

    BackHandler(enabled = vm.overlay != Overlay.None || vm.selection.isNotEmpty()) {
        if (vm.overlay != Overlay.None) vm.closeOverlay() else vm.clearSelection()
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
    val selecting = vm.selection.isNotEmpty()

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
            if (selecting) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = vm::clearSelection) { Icon(Icons.Default.Close, "Cancel selection") }
                    Text("${vm.selection.size} selected", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = vm::restoreSelected) { Text("Restore to gallery") }
                    TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = Danger) }
                }
            } else {
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
                    }
                    IconButton(onClick = vm::openSettings) { Icon(Icons.Default.Settings, "Settings") }
                    IconButton(onClick = vm::lock) { Icon(Icons.Default.Lock, "Lock now") }
                }
            }

            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())

            if (vm.items.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Your vault is empty.\nTap + to add photos and videos.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(vm.items, key = { _, item -> item.id }) { index, item ->
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
    val count = vm.items.size
    if (count == 0) {
        LaunchedEffect(Unit) { vm.closeOverlay() }
        return
    }
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, count - 1), pageCount = { vm.items.size })

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            vm.items.getOrNull(page)?.let { MediaPage(vm, it, active = pager.currentPage == page) }
        }
        Row(
            Modifier.fillMaxWidth().safeDrawingPadding().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = vm::closeOverlay) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
            Text(
                vm.items.getOrNull(pager.currentPage)?.name.orEmpty(),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MediaPage(vm: VaultViewModel, item: VaultItem, active: Boolean) {
    if (item.isVideo) VideoPage(vm, item, active) else ImagePage(vm, item)
}

@Composable
private fun ImagePage(vm: VaultViewModel, item: VaultItem) {
    val state by produceState(initialValue = false to (null as Bitmap?), item.id) {
        value = true to vm.fullImage(item)
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val (loaded, bmp) = state
        when {
            !loaded -> CircularProgressIndicator()
            bmp == null -> Text("This image can't be displayed.", color = Color.White)
            else -> Image(bmp.asImageBitmap(), item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
    }
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
    var current by rememberSaveable { mutableStateOf("") }
    var newPass by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var passMessage by remember { mutableStateOf<String?>(null) }
    var passIsError by remember { mutableStateOf(false) }

    var showWipe by remember { mutableStateOf(false) }
    var wipePass by rememberSaveable { mutableStateOf("") }
    var wipeWrong by remember { mutableStateOf(false) }

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

        SectionTitle("Good to know")
        Text(
            "Adding items asks Android to delete the originals from your gallery. Copies that were " +
                "already backed up to a cloud service (for example Google Photos) are not touched — " +
                "remove those yourself if you want them gone.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

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
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = Mint)
}
