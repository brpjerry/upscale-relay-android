package org.upscalerelay.android

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import android.media.AudioManager
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.outlined.Toc
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SortByAlpha
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.upscalerelay.client.RelaySessionController
import org.upscalerelay.client.SessionState
import org.upscalerelay.player.mpv.MpvPlaybackState
import org.upscalerelay.player.mpv.MpvSurfaceView
import org.upscalerelay.player.mpv.MpvTrack
import org.upscalerelay.protocol.ChapterInfo
import org.upscalerelay.protocol.LibraryNode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun RelayApp(viewModel: RelayViewModel, inPictureInPicture: Boolean = false) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val playing = state.playingPath != null
    LandscapePlaybackOrientation(active = playing && !inPictureInPicture)
    SystemBars(dark = dark, immersive = playing)

    // Material You on Android 12+: take the palette from the system so the
    // app matches the rest of the device. The static schemes below are the
    // pre-12 fallback; they only overrode four roles, which left every other
    // surface on Material's purple-tinted baseline — the source of the odd
    // near-white shades.
    val themeContext = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(themeContext) else dynamicLightColorScheme(themeContext)
        dark -> phaseThreeDarkColors()
        else -> phaseThreeLightColors()
    }
    MaterialTheme(colorScheme = colorScheme) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size -> viewModel.updateDisplaySize(size.width, size.height) },
            color = MaterialTheme.colorScheme.background,
        ) {
            when {
                playing -> PlayerScreen(viewModel, state, inPictureInPicture)
                // Wait for persisted state before drawing any destination so
                // restoration never flashes an obsolete screen.
                !state.preferencesLoaded -> Box(Modifier.fillMaxSize())
                else -> TabletShell(viewModel, state)
            }
        }
    }
}

private fun phaseThreeDarkColors(): ColorScheme = darkColorScheme(
    primary = Color(0xff9ecaff),
    secondary = Color(0xffb8c8dc),
    background = Color(0xff111418),
    surface = Color(0xff111418),
    surfaceVariant = Color(0xff252a31),
    surfaceContainerLowest = Color(0xff0c0f12),
    surfaceContainerLow = Color(0xff181c21),
    surfaceContainer = Color(0xff1c2127),
    surfaceContainerHigh = Color(0xff262b32),
    surfaceContainerHighest = Color(0xff30363e),
)

private fun phaseThreeLightColors() = lightColorScheme(
    primary = Color(0xff245f99),
    secondary = Color(0xff4f6072),
    background = Color(0xfff8f9fd),
    surface = Color(0xfff8f9fd),
    surfaceVariant = Color(0xffe8edf4),
    surfaceContainerLowest = Color(0xffffffff),
    surfaceContainerLow = Color(0xfff2f4f9),
    surfaceContainer = Color(0xffecEff6),
    surfaceContainerHigh = Color(0xffe6e9f0),
    surfaceContainerHighest = Color(0xffe0e4eb),
)

@Composable
private fun SystemBars(dark: Boolean, immersive: Boolean) {
    val activity = LocalContext.current.findActivity()
    val view = LocalView.current
    DisposableEffect(activity, view, dark, immersive) {
        if (activity == null) return@DisposableEffect onDispose { }
        val controller = WindowCompat.getInsetsController(activity.window, view)
        controller.isAppearanceLightStatusBars = !dark && !immersive
        controller.isAppearanceLightNavigationBars = !dark && !immersive
        if (immersive) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (immersive) controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable
private fun LandscapePlaybackOrientation(active: Boolean) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity, active) {
        if (!active || activity == null) return@DisposableEffect onDispose { }
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            if (!activity.isChangingConfigurations) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * A LazyListState whose scroll position outlives the composable: it is seeded
 * from and saved back to the view model, so opening the player (which removes
 * the browser from composition) or reconnecting does not reset the list.
 */
@Composable
private fun rememberPersistedListState(viewModel: RelayViewModel, key: String): LazyListState {
    val listState = remember(key) {
        val (index, offset) = viewModel.savedListScroll(key)
        LazyListState(index, offset)
    }
    DisposableEffect(key) {
        onDispose {
            viewModel.saveListScroll(
                key,
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
            )
        }
    }
    return listState
}

/** True for phone-sized windows; the shell swaps rail for a bottom bar. */
@Composable
private fun isCompactWidth(): Boolean = LocalConfiguration.current.screenWidthDp < 600

@Composable
private fun contentPadding() = if (isCompactWidth()) 16.dp else 28.dp

@Composable
private fun TabletShell(viewModel: RelayViewModel, state: RelayUiState) {
    if (isCompactWidth()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Box(Modifier.weight(1f).fillMaxWidth()) { DestinationContent(viewModel, state) }
            NavigationBar {
                TabletDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = state.destination == destination,
                        onClick = { viewModel.selectDestination(destination) },
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { Text(destination.label) },
                    )
                }
            }
        }
        return
    }
    Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        NavigationRail(
            modifier = Modifier.fillMaxHeight().width(104.dp),
            header = {
                Text(
                    "UR",
                    modifier = Modifier.padding(vertical = 20.dp),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            },
        ) {
            TabletDestination.entries.forEach { destination ->
                NavigationRailItem(
                    selected = state.destination == destination,
                    onClick = { viewModel.selectDestination(destination) },
                    icon = { Icon(destination.icon, contentDescription = destination.label) },
                    label = { Text(destination.label) },
                    alwaysShowLabel = true,
                )
            }
        }
        VerticalDivider()
        Box(Modifier.weight(1f).fillMaxHeight()) { DestinationContent(viewModel, state) }
    }
}

@Composable
private fun DestinationContent(viewModel: RelayViewModel, state: RelayUiState) {
    when (state.destination) {
        TabletDestination.SERVER -> ServerDestination(viewModel, state)
        TabletDestination.LOCAL -> LocalDestination(viewModel, state)
        TabletDestination.RECENT -> RecentDestination(viewModel, state)
        TabletDestination.SETTINGS -> SettingsDestination(viewModel, state)
    }
}

private val TabletDestination.label: String
    get() = when (this) {
        TabletDestination.SERVER -> "Server"
        TabletDestination.LOCAL -> "Local"
        TabletDestination.RECENT -> "Recent"
        TabletDestination.SETTINGS -> "Settings"
    }

private val TabletDestination.icon
    get() = when (this) {
        TabletDestination.SERVER -> Icons.Outlined.Dns
        TabletDestination.LOCAL -> Icons.Outlined.Folder
        TabletDestination.RECENT -> Icons.Outlined.History
        TabletDestination.SETTINGS -> Icons.Outlined.Settings
    }

@Composable
private fun LocalDestination(viewModel: RelayViewModel, state: RelayUiState) {
    val context = LocalContext.current
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            viewModel.openLocalDocument(uri.toString())
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            viewModel.openLocalTree(uri.toString())
        }
    }
    BackHandler(enabled = state.localCanGoUp) { viewModel.upLocalDirectory() }
    Column(Modifier.fillMaxSize().padding(contentPadding())) {
        DestinationHeader("Local files", "Android document picker · persisted read access")
        state.error?.let { InlineError(it, viewModel::dismissError) }
        Spacer(Modifier.height(24.dp))
        if (state.capabilities == null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp)) {
                    Text("Connect to the upscale server first", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("Local video stays on this tablet except for its encoded video packets, which are sent to the relay server.")
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = viewModel::connect, enabled = !state.busy) { Text("Connect") }
                }
            }
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { filePicker.launch(arrayOf("video/*", "application/x-matroska")) },
                enabled = !state.busy,
            ) { Text("Choose video") }
            OutlinedButton(
                onClick = { folderPicker.launch(null) },
                enabled = !state.busy,
            ) { Text("Choose folder") }
        }
        Spacer(Modifier.height(20.dp))
        val localListState =
            rememberPersistedListState(viewModel, "local:${state.localDirectoryName.orEmpty()}")
        LazyColumn(state = localListState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.localDirectoryName?.let { directoryName ->
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.localCanGoUp) {
                            TextButton(onClick = viewModel::upLocalDirectory, enabled = !state.busy) {
                                Text("← Up")
                            }
                        }
                        Text(
                            directoryName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        SortToggle(
                            sort = state.librarySort,
                            enabled = !state.busy,
                            onChange = viewModel::setLibrarySort,
                        )
                    }
                }
                if (state.localEntries.isEmpty()) {
                    item {
                        Text("This folder is empty.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                // Keys are namespaced: the same document can legitimately
                // appear in the tree listing and in the recents below.
                items(state.localEntries, key = { "entry:${it.uri}" }) { entry ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !state.busy) {
                            viewModel.openLocalEntry(entry)
                        },
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (entry.isDirectory) Icons.Outlined.Folder else Icons.Outlined.Movie,
                                contentDescription = if (entry.isDirectory) "Folder" else "Video",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(entry.name, fontWeight = FontWeight.Medium)
                                if (!entry.isDirectory) {
                                    Text(
                                        entry.mimeType.ifEmpty { "Video file" },
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
            item { Text("Recent folders", style = MaterialTheme.typography.titleMedium) }
            if (state.recentLocalRootUris.isEmpty()) {
                item { Text("No local folders opened yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(state.recentLocalRootUris, key = { "root:$it" }) { value ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !state.busy) {
                        viewModel.openLocalTree(value)
                    },
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(localUriLabel(value), fontWeight = FontWeight.Medium)
                        Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
            item { Text("Recent local files", style = MaterialTheme.typography.titleMedium) }
            if (state.recentLocalUris.isEmpty()) {
                item { Text("No local files opened yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(state.recentLocalUris, key = { "file:$it" }) { value ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !state.busy) {
                            viewModel.openRecentLocal(value)
                        },
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(localUriLabel(value), fontWeight = FontWeight.Medium)
                            Text(
                                value,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun localUriLabel(value: String): String {
    val segment = value.toUri().lastPathSegment?.substringAfterLast('/') ?: return "Local video"
    // Providers like downloads use opaque document ids ("msf:12345") that
    // mean nothing to the user; show a human label instead.
    return if (':' in segment && segment.substringAfter(':').all(Char::isDigit)) {
        "Downloads video"
    } else {
        segment
    }
}

private val SessionState.userLabel: String
    get() = when (this) {
        SessionState.DISCONNECTED -> "Disconnected"
        SessionState.CONNECTING -> "Connecting…"
        SessionState.BROWSING -> "Connected"
        SessionState.FAILED -> "Connection lost"
        else -> "Busy"
    }

@Composable
private fun ServerDestination(viewModel: RelayViewModel, state: RelayUiState) {
    if (state.currentDirectory == null) {
        ConnectPanel(viewModel, state)
        return
    }
    val compact = isCompactWidth()
    val detailShown = compact && state.selectedLibraryNode != null
    BackHandler(enabled = detailShown) { viewModel.clearLibrarySelection() }
    BackHandler(enabled = !detailShown && state.directoryStack.isNotEmpty()) {
        viewModel.upDirectory()
    }
    Column(Modifier.fillMaxSize().padding(contentPadding())) {
        DestinationHeader(
            title = state.capabilities?.serverName ?: "Server library",
            subtitle = "${state.host}:${state.port}  ·  ${state.sessionState.userLabel}",
            action = { OutlinedButton(onClick = viewModel::connect, enabled = !state.busy) { Text("Reconnect") } },
        )
        state.error?.let { InlineError(it, viewModel::dismissError) }
        Spacer(Modifier.height(if (compact) 12.dp else 20.dp))
        when {
            detailShown -> Column(Modifier.fillMaxSize()) {
                TextButton(onClick = viewModel::clearLibrarySelection) { Text("← All videos") }
                LibraryDetail(
                    viewModel = viewModel,
                    state = state,
                    node = state.selectedLibraryNode,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
            compact -> LibraryList(viewModel, state, Modifier.fillMaxSize())
            else -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                LibraryList(viewModel, state, Modifier.weight(1.15f).fillMaxHeight())
                VerticalDivider()
                LibraryDetail(
                    viewModel = viewModel,
                    state = state,
                    node = state.selectedLibraryNode,
                    modifier = Modifier.weight(0.85f).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun LibraryList(viewModel: RelayViewModel, state: RelayUiState, modifier: Modifier) {
    val directory = state.currentDirectory ?: return
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.directoryStack.isNotEmpty()) {
                TextButton(onClick = viewModel::upDirectory) { Text("← Up") }
            }
            Text(
                directory.path.ifEmpty { "Library" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // Date order needs server-side support; old servers only sort by
            // name, so the toggle is hidden rather than silently wrong.
            if ("mtime" in state.capabilities?.librarySortKeys.orEmpty()) {
                SortToggle(
                    sort = state.librarySort,
                    enabled = !state.busy && !state.libraryLoading,
                    onChange = viewModel::setLibrarySort,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        val listState = rememberPersistedListState(viewModel, "server:${directory.path}")
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(directory.children, key = { it.path }) { node ->
                LibraryItem(
                    node = node,
                    selected = state.selectedLibraryNode?.path == node.path,
                    enabled = !state.busy && !state.libraryLoading,
                ) {
                    if (node.type == LibraryNode.Type.DIRECTORY) viewModel.openDirectory(node)
                    else viewModel.selectLibraryNode(node)
                }
            }
            if (state.libraryLoading || state.libraryNextCursor != null) {
                item(key = "library-page-footer") {
                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                        if (state.libraryLoading) {
                            CircularProgressIndicator(Modifier.size(28.dp))
                        } else {
                            TextButton(onClick = viewModel::loadMoreLibrary) { Text("Load more") }
                        }
                    }
                }
            }
        }
    }
}

/** Two-way switch between alphabetical and newest-first file ordering. */
@Composable
private fun SortToggle(sort: LibrarySort, enabled: Boolean, onChange: (LibrarySort) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = sort == LibrarySort.NAME,
            onClick = { onChange(LibrarySort.NAME) },
            enabled = enabled,
            label = { Text("A–Z") },
            leadingIcon = {
                Icon(Icons.Outlined.SortByAlpha, contentDescription = null, Modifier.size(18.dp))
            },
        )
        FilterChip(
            selected = sort == LibrarySort.DATE,
            onClick = { onChange(LibrarySort.DATE) },
            enabled = enabled,
            label = { Text("Newest") },
            leadingIcon = {
                Icon(Icons.Outlined.Schedule, contentDescription = null, Modifier.size(18.dp))
            },
        )
    }
}

@Composable
private fun ConnectPanel(viewModel: RelayViewModel, state: RelayUiState) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Card(
            Modifier.fillMaxWidth(if (isCompactWidth()) 1f else 0.72f),
            colors = CardDefaults.cardColors(),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Connect to your relay", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Browse and play the server library from this tablet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = state.host,
                        onValueChange = viewModel::setHost,
                        label = { Text("Server host") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = state.port,
                        onValueChange = viewModel::setPort,
                        label = { Text("Port") },
                        singleLine = true,
                        modifier = Modifier.width(150.dp),
                    )
                }
                state.error?.let {
                    Spacer(Modifier.height(16.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(24.dp))
                Button(onClick = viewModel::connect, enabled = !state.busy && state.preferencesLoaded) {
                    if (state.busy) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(if (state.busy) "Connecting" else "Connect")
                }
                if (state.discoveredServers.isNotEmpty()) {
                    Spacer(Modifier.height(28.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Discovered on this network",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    state.discoveredServers.forEach { server ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable(enabled = !state.busy) { viewModel.connectTo(server) },
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(server.serviceName, fontWeight = FontWeight.Medium)
                                    Text(
                                        "${server.host}:${server.port}",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Text("Connect", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DestinationHeader(
    title: String,
    subtitle: String,
    action: @Composable () -> Unit = {},
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action()
    }
}

@Composable
private fun InlineError(message: String, onDismiss: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun LibraryItem(node: LibraryNode, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (node.type == LibraryNode.Type.DIRECTORY) Icons.Outlined.Folder else Icons.Outlined.Movie,
                contentDescription = if (node.type == LibraryNode.Type.DIRECTORY) "Folder" else "Video",
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(16.dp))
            Text(node.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LibraryDetail(
    viewModel: RelayViewModel,
    state: RelayUiState,
    node: LibraryNode?,
    modifier: Modifier = Modifier,
) {
    Box(modifier.padding(12.dp), contentAlignment = Alignment.Center) {
        if (node == null) {
            Text("Select a video to see playback details.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Box
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                Modifier.size(180.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "Play",
                        modifier = Modifier.size(96.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(node.name, style = MaterialTheme.typography.titleLarge, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            Text(
                "${state.selectedModel.ifEmpty { "Default model" }}  ·  ${state.qualityTier}  ·  " +
                    "${state.fitMode}  ·  ${state.resizeAlgorithm.ifEmpty { "Server resize" }}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = { viewModel.openFile(node) }, enabled = !state.busy) {
                if (state.busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Text(if (state.busy) "Opening" else "Play")
            }
        }
    }
}

@Composable
private fun RecentDestination(viewModel: RelayViewModel, state: RelayUiState) {
    Column(Modifier.fillMaxSize().padding(contentPadding())) {
        DestinationHeader(
            title = "Recent",
            subtitle = "Your latest server-library videos",
            action = {
                if (state.recentPaths.isNotEmpty()) {
                    TextButton(onClick = viewModel::clearRecents) { Text("Clear") }
                }
            },
        )
        Spacer(Modifier.height(20.dp))
        if (state.recentPaths.isEmpty()) {
            PlaceholderCard("Videos you open will appear here.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.recentPaths, key = { it }) { path ->
                    Card(Modifier.fillMaxWidth().clickable { viewModel.openRecent(path) }) {
                        Column(Modifier.padding(18.dp)) {
                            Text(path.substringAfterLast('/'), style = MaterialTheme.typography.titleMedium)
                            Text(path, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsDestination(viewModel: RelayViewModel, state: RelayUiState) {
    Column(Modifier.fillMaxSize().padding(contentPadding()).imePadding()) {
        DestinationHeader("Settings", "Tablet and relay preferences")
        Spacer(Modifier.height(20.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                SettingsSection("Connection") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = state.host,
                            onValueChange = viewModel::setHost,
                            label = { Text("Server host") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = state.port,
                            onValueChange = viewModel::setPort,
                            label = { Text("Port") },
                            singleLine = true,
                            modifier = Modifier.width(150.dp),
                        )
                        Button(onClick = viewModel::connect, enabled = !state.busy) { Text("Connect") }
                    }
                    SettingToggle("Connect automatically", state.autoConnect, viewModel::setAutoConnect)
                    SettingToggle(
                        "Reconnect automatically during playback",
                        state.autoResume,
                        viewModel::setAutoResume,
                    )
                    if (state.discoveredServers.isNotEmpty()) {
                        Text("Discovered servers", style = MaterialTheme.typography.labelLarge)
                        state.discoveredServers.forEach { server ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !state.busy) { viewModel.connectTo(server) }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(server.serviceName)
                                    Text(
                                        "${server.host}:${server.port}",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Text("Connect", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
            item {
                SettingsSection("Playback defaults") {
                    Text("Model", style = MaterialTheme.typography.labelLarge)
                    state.capabilities?.models.orEmpty().forEach { model ->
                        RadioSetting(model.name, state.selectedModel == model.name) { viewModel.setModel(model.name) }
                    }
                    Text("Quality", style = MaterialTheme.typography.labelLarge)
                    state.capabilities?.qualityOptions.orEmpty()
                        .filter { it.androidSupported && it.id in RelaySessionController.ANDROID_HEVC_TIERS }
                        .forEach { option ->
                            RadioSetting(option.label, state.qualityTier == option.id) {
                                viewModel.setQualityTier(option.id)
                            }
                        }
                    Text("Framing", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RelaySessionController.FIT_MODES.forEach { mode ->
                            FilterChip(
                                selected = state.fitMode == mode,
                                onClick = { viewModel.setFitMode(mode) },
                                label = { Text(mode.replaceFirstChar(Char::uppercase)) },
                            )
                        }
                    }
                    Text("Downscale filter", style = MaterialTheme.typography.labelLarge)
                    RadioSetting("Server default", state.resizeAlgorithm.isEmpty()) {
                        viewModel.setResizeAlgorithm("")
                    }
                    state.capabilities?.resizeAlgorithms.orEmpty().forEach { algorithm ->
                        RadioSetting(algorithm, state.resizeAlgorithm == algorithm) {
                            viewModel.setResizeAlgorithm(algorithm)
                        }
                    }
                    SettingToggle("Enable subtitles by default", state.subtitlesEnabled, viewModel::setSubtitlesEnabled)
                    SettingToggle("GPU deband", state.debandEnabled, viewModel::setDebandEnabled)
                }
            }
            item {
                SettingsSection("Player") {
                    SettingToggle("Touch gestures", state.gesturesEnabled, viewModel::setGesturesEnabled)
                    SettingToggle("Diagnostic overlay", state.diagnosticsVisible, viewModel::setDiagnosticsVisible)
                    SettingToggle(
                        "Continue playback in the background",
                        state.backgroundPlayback,
                        viewModel::setBackgroundPlayback,
                    )
                    SettingToggle(
                        "Save diagnostic log to Documents",
                        state.fileLoggingEnabled,
                        viewModel::setFileLoggingEnabled,
                    )
                    state.logFileName?.let { name ->
                        Text(
                            "Logging to Documents/UpscaleRelay/$name",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    VideoSyncPreferenceControls(viewModel, state)
                }
            }
        }
    }
}

/**
 * Typed equivalents of the mpv.conf video-sync/interpolation/tscale
 * preferences. Relay plumbing (vo, rebase-start-time, hwdec, epoch reloads)
 * is not user-configurable.
 */
@Composable
private fun VideoSyncPreferenceControls(viewModel: RelayViewModel, state: RelayUiState) {
    Column {
        SettingToggle(
            "Display-resample video sync",
            state.displayResampleSync,
            viewModel::setDisplayResampleSync,
        )
        if (state.displayResampleSync) {
            SettingToggle(
                "Motion interpolation",
                state.interpolationEnabled,
                viewModel::setInterpolationEnabled,
            )
        }
        if (state.displayResampleSync && state.interpolationEnabled) {
            Text("Interpolation scaler", style = MaterialTheme.typography.labelLarge)
            org.upscalerelay.player.mpv.MpvPlayerEngine.INTERPOLATION_SCALERS.forEach { scaler ->
                RadioSetting(scaler, state.interpolationScaler == scaler) {
                    viewModel.setInterpolationScaler(scaler)
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun SettingToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun RadioSetting(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

@Composable
private fun PlaceholderDestination(title: String, body: String) {
    Column(Modifier.fillMaxSize().padding(contentPadding())) {
        DestinationHeader(title, "Coming in a later phase")
        Spacer(Modifier.height(24.dp))
        PlaceholderCard(body)
    }
}

@Composable
private fun PlaceholderCard(body: String) {
    Card(Modifier.fillMaxWidth()) {
        Text(body, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerScreen(
    viewModel: RelayViewModel,
    state: RelayUiState,
    inPictureInPicture: Boolean = false,
) {
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsLocked by remember { mutableStateOf(false) }
    var lockedButtonVisible by remember { mutableStateOf(false) }
    var trackSheetVisible by remember { mutableStateOf(false) }
    var settingsSheetVisible by remember { mutableStateOf(false) }
    var chapterSheetVisible by remember { mutableStateOf(false) }
    var gestureMessage by remember { mutableStateOf<String?>(null) }
    val duration = (state.session?.durationSeconds ?: state.mpvMetrics.durationSeconds).coerceAtLeast(0.0)
    // Show what the user asked for, not what the pipeline momentarily reports:
    // an active scrub wins, then a committed-but-still-restarting seek target,
    // and only then mpv's own clock.
    val position = (state.seekPreviewSeconds ?: state.seekTargetSeconds ?: state.mpvMetrics.positionSeconds)
        .coerceIn(0.0, duration.coerceAtLeast(0.001))

    // Back always returns to the library — including from the ENDED state,
    // which previously fell through and exited the application.
    BackHandler { viewModel.closePlayback() }

    if (inPictureInPicture) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { context -> MpvSurfaceView(context).apply { engine = viewModel.playerEngine } },
                update = { it.engine = viewModel.playerEngine },
                modifier = Modifier.fillMaxSize(),
            )
        }
        return
    }
    LaunchedEffect(controlsVisible, controlsLocked, state.paused, state.seeking, trackSheetVisible, settingsSheetVisible, chapterSheetVisible) {
        if (controlsVisible && !controlsLocked && !state.paused && !state.seeking && !trackSheetVisible && !settingsSheetVisible && !chapterSheetVisible) {
            delay(4_000)
            controlsVisible = false
        }
    }
    LaunchedEffect(lockedButtonVisible, controlsLocked) {
        if (controlsLocked && lockedButtonVisible) {
            delay(4_000)
            lockedButtonVisible = false
        }
    }
    LaunchedEffect(gestureMessage) {
        if (gestureMessage != null) {
            delay(1_200)
            gestureMessage = null
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { context -> MpvSurfaceView(context).apply { engine = viewModel.playerEngine } },
            update = { it.engine = viewModel.playerEngine },
            modifier = Modifier.fillMaxSize(),
        )
        PlayerTouchLayer(
            viewModel = viewModel,
            state = state,
            duration = duration,
            enabled = state.gesturesEnabled && !controlsLocked,
            onToggleControls = {
                if (controlsLocked) {
                    lockedButtonVisible = !lockedButtonVisible
                } else {
                    controlsVisible = !controlsVisible
                }
            },
            onShowControls = { controlsVisible = true },
            onMessage = { gestureMessage = it },
        )
        if (controlsVisible && !controlsLocked) {
            PlayerChrome(
                viewModel = viewModel,
                state = state,
                position = position,
                duration = duration,
                onTracks = { trackSheetVisible = true },
                onSettings = { settingsSheetVisible = true },
                onChapters = { chapterSheetVisible = true },
                onLock = {
                    controlsLocked = true
                    controlsVisible = false
                    lockedButtonVisible = true
                    gestureMessage = null
                },
            )
        }
        if (controlsLocked && lockedButtonVisible) {
            LockedControlsButton(
                onUnlock = {
                    controlsLocked = false
                    lockedButtonVisible = false
                    controlsVisible = true
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp),
            )
        }
        // Buffering indicator: mpv is starved (mid-play rebuffer) or still
        // loading after a seek/reload. Independent of the controls layer so
        // stalls are visible even with the chrome hidden. The pre-endpoint
        // phase has its own "Preparing" overlay below.
        val buffering = state.endpoint != null && state.error == null &&
            state.reconnecting == null && !state.paused &&
            (
                state.mpvMetrics.pausedForCache ||
                    state.playerState == MpvPlaybackState.LOADING ||
                    state.playerState == MpvPlaybackState.LOADED
                )
        if (buffering) {
            Surface(
                modifier = Modifier.align(Alignment.Center),
                color = Color(0x66000000),
                shape = CircleShape,
            ) {
                CircularProgressIndicator(
                    Modifier.padding(18.dp).size(44.dp),
                    color = Color.White,
                    strokeWidth = 4.dp,
                )
            }
        }
        gestureMessage?.let { message ->
            Surface(
                modifier = Modifier.align(Alignment.Center),
                color = Color(0xcc111111),
                shape = MaterialTheme.shapes.large,
            ) {
                Text(message, Modifier.padding(horizontal = 28.dp, vertical = 18.dp), color = Color.White)
            }
        }
        if (state.endpoint == null && state.error == null && state.reconnecting == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(14.dp))
                Text("Preparing ${state.qualityTier}…", color = Color.White)
                // session_progress keepalive text, e.g. a first-use TensorRT
                // engine build that runs for minutes.
                state.openingProgress?.let { progress ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        progress,
                        color = Color.LightGray,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 48.dp),
                    )
                }
            }
        }
        state.performanceWarning?.let { warning ->
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = if (controlsVisible && !controlsLocked) 92.dp else 24.dp),
                color = Color(0xdd3a2f12),
                shape = MaterialTheme.shapes.large,
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        warning,
                        color = Color(0xffffdf9e),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    TextButton(onClick = viewModel::dismissPerformanceWarning) {
                        Text("Dismiss", color = Color.White)
                    }
                }
            }
        }
        state.reconnecting?.let { status ->
            ReconnectOverlay(
                status = status,
                canFallback = state.localPlayback && !state.directLocalFallback,
                onFallback = viewModel::playLocalFallback,
                onCancel = viewModel::cancelAutoResume,
            )
        }
        if (state.reconnecting == null) {
            state.error?.let { error ->
                PlayerError(
                    message = error,
                    onBack = viewModel::closePlayback,
                    onRetry = viewModel::retry,
                    canFallback = state.localPlayback && !state.directLocalFallback,
                    onFallback = viewModel::playLocalFallback,
                )
            }
        }
    }

    if (trackSheetVisible) {
        TrackSheet(viewModel, state) { trackSheetVisible = false }
    }
    if (settingsSheetVisible) {
        PlaybackSettingsSheet(viewModel, state) { settingsSheetVisible = false }
    }
    if (chapterSheetVisible) {
        ChapterSheet(viewModel, state) { chapterSheetVisible = false }
    }
}

@Composable
private fun PlayerTouchLayer(
    viewModel: RelayViewModel,
    state: RelayUiState,
    duration: Double,
    enabled: Boolean,
    onToggleControls: () -> Unit,
    onShowControls: () -> Unit,
    onMessage: (String?) -> Unit,
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val audio = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val currentPosition by rememberUpdatedState(state.mpvMetrics.positionSeconds)
    var size by remember { mutableStateOf(IntSize.Zero) }
    var totalDrag by remember { mutableStateOf(Offset.Zero) }
    var dragStart by remember { mutableStateOf(Offset.Zero) }
    var startPosition by remember { mutableStateOf(0.0) }
    var startBrightness by remember { mutableStateOf(0.5f) }
    var startVolume by remember { mutableStateOf(0) }
    var horizontalSeek by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) { detectTapGestures(onTap = { onToggleControls() }) }
            .then(
                if (!enabled) Modifier else Modifier.pointerInput(duration, size) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            onShowControls()
                            dragStart = offset
                            totalDrag = Offset.Zero
                            startPosition = currentPosition
                            startBrightness = activity?.window?.attributes?.screenBrightness
                                ?.takeIf { it >= 0f } ?: 0.5f
                            startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                            horizontalSeek = false
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            totalDrag += amount
                            if (abs(totalDrag.x) > abs(totalDrag.y)) {
                                horizontalSeek = true
                                val seconds = (startPosition + totalDrag.x / size.width.coerceAtLeast(1) * 600.0)
                                    .coerceIn(0.0, duration.coerceAtLeast(0.0))
                                viewModel.previewSeek(seconds)
                                onMessage("Seek  ${formatTime(seconds)}")
                            } else if (size.height > 0) {
                                val changeFraction = -totalDrag.y / size.height
                                if (dragStart.x < size.width / 2f) {
                                    val next = (startBrightness + changeFraction).coerceIn(0.02f, 1f)
                                    activity?.window?.let { window ->
                                        val attributes = window.attributes
                                        attributes.screenBrightness = next
                                        window.attributes = attributes
                                    }
                                    onMessage("Brightness  ${(next * 100).roundToInt()}%")
                                } else {
                                    val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                    val next = (startVolume + changeFraction * maximum).roundToInt().coerceIn(0, maximum)
                                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
                                    onMessage("Volume  ${(next * 100f / maximum).roundToInt()}%")
                                }
                            }
                        },
                        onDragEnd = {
                            if (horizontalSeek) viewModel.commitSeek()
                            horizontalSeek = false
                        },
                        onDragCancel = {
                            viewModel.cancelSeekPreview()
                            horizontalSeek = false
                        },
                    )
                },
            ),
    )
}

@Composable
private fun PlayerChrome(
    viewModel: RelayViewModel,
    state: RelayUiState,
    position: Double,
    duration: Double,
    onTracks: () -> Unit,
    onSettings: () -> Unit,
    onChapters: () -> Unit,
    onLock: () -> Unit,
) {
    val chapters = state.session?.chapters.orEmpty()
    val currentChapter = chapters.lastOrNull { it.startSeconds <= position }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xdd000000), Color.Transparent)))
                .padding(horizontal = 24.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = viewModel::closePlayback) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to library",
                    tint = Color.White,
                )
            }
            Column(Modifier.weight(1f).padding(horizontal = 18.dp)) {
                Text(
                    state.playingPath?.substringAfterLast('/').orEmpty(),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(state.sessionDescription, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
            }
            if (chapters.isNotEmpty()) {
                TextButton(onClick = onChapters) {
                    Icon(Icons.AutoMirrored.Outlined.Toc, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("Chapters", color = Color.White)
                }
            }
            TextButton(onClick = onTracks) {
                Icon(Icons.Outlined.Subtitles, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text("Audio & subtitles", color = Color.White)
            }
            TextButton(onClick = onSettings) {
                Icon(Icons.Outlined.Tune, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text("Playback", color = Color.White)
            }
            IconButton(onClick = onLock) {
                Icon(
                    Icons.Outlined.Lock,
                    contentDescription = "Lock player controls",
                    tint = Color.White,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xee000000))))
                .navigationBarsPadding()
                .padding(horizontal = 36.dp, vertical = 18.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(formatTime(position), color = Color.White, style = MaterialTheme.typography.labelLarge)
                PlayerSeekBar(
                    position = position,
                    duration = duration,
                    chapters = chapters,
                    enabled = duration > 0,
                    onScrub = { viewModel.previewSeek(it) },
                    onScrubFinished = viewModel::commitSeek,
                    onScrubCancelled = viewModel::cancelSeekPreview,
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                )
                Text(formatTime(duration), color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(4.dp))
            // A Box keeps the transport cluster truly centered: with weight
            // spacers, uneven side content pushed it off-center.
            Box(Modifier.fillMaxWidth()) {
                currentChapter?.let { chapter ->
                    Row(
                        Modifier.align(Alignment.CenterStart).fillMaxWidth(0.26f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.Toc,
                            contentDescription = null,
                            tint = Color.LightGray,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            chapter.title ?: "Chapter ${chapters.indexOf(chapter) + 1}",
                            color = Color.LightGray,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(
                    Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (chapters.isNotEmpty()) {
                        PlayerRoundButton(Icons.Filled.SkipPrevious, "Previous chapter") {
                            viewModel.chapterStep(-1)
                        }
                    }
                    PlayerRoundButton(Icons.Filled.Replay10, "Back 10 seconds") {
                        viewModel.seekRelative(-10.0)
                    }
                    FilledIconButton(
                        onClick = viewModel::togglePaused,
                        enabled = !state.seeking,
                        modifier = Modifier.size(64.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color.White,
                            contentColor = Color.Black,
                            disabledContainerColor = Color.White.copy(alpha = 0.7f),
                            disabledContentColor = Color.Black,
                        ),
                    ) {
                        if (state.seeking) {
                            CircularProgressIndicator(
                                Modifier.size(26.dp),
                                color = Color.Black,
                                strokeWidth = 2.5.dp,
                            )
                        } else {
                            Icon(
                                if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                                contentDescription = if (state.paused) "Play" else "Pause",
                                modifier = Modifier.size(34.dp),
                            )
                        }
                    }
                    PlayerRoundButton(Icons.Filled.Forward10, "Forward 10 seconds") {
                        viewModel.seekRelative(10.0)
                    }
                    if (chapters.isNotEmpty()) {
                        PlayerRoundButton(Icons.Filled.SkipNext, "Next chapter") {
                            viewModel.chapterStep(1)
                        }
                    }
                }
            }
            if (state.diagnosticsVisible) {
                Text(
                    diagnosticsLine(state),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    color = Color.White.copy(alpha = 0.55f),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PlayerRoundButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = Color.White.copy(alpha = 0.14f),
            contentColor = Color.White,
        ),
    ) {
        Icon(icon, contentDescription = description)
    }
}

/**
 * Intent-first scrub bar: a rounded track with an accent-gradient elapsed
 * segment, chapter dots, and a grow-on-touch thumb with a time bubble. All
 * values render from the caller's `position`, which already prefers the
 * user's scrub/seek target over raw player state.
 */
@Composable
private fun PlayerSeekBar(
    position: Double,
    duration: Double,
    chapters: List<ChapterInfo>,
    enabled: Boolean,
    onScrub: (Double) -> Unit,
    onScrubFinished: () -> Unit,
    onScrubCancelled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf(false) }
    var barWidthPx by remember { mutableStateOf(0) }
    var bubbleWidthPx by remember { mutableStateOf(0) }
    val accent = MaterialTheme.colorScheme.primary
    val accentBright = lerp(accent, Color.White, 0.4f)
    val trackHeight by animateDpAsState(if (dragging) 6.dp else 4.dp, label = "seekTrackHeight")
    val thumbRadius by animateDpAsState(if (dragging) 9.dp else 6.dp, label = "seekThumbRadius")
    val fraction = if (duration > 0) (position / duration).toFloat().coerceIn(0f, 1f) else 0f

    fun secondsAt(x: Float): Double {
        if (duration <= 0 || barWidthPx <= 0) return 0.0
        return (x / barWidthPx).coerceIn(0f, 1f) * duration
    }

    Box(
        modifier
            .height(44.dp)
            .onSizeChanged { barWidthPx = it.width }
            .pointerInput(enabled, duration) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    onScrub(secondsAt(offset.x))
                    onScrubFinished()
                }
            }
            .pointerInput(enabled, duration) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        onScrub(secondsAt(offset.x))
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        onScrub(secondsAt(change.position.x))
                    },
                    onDragEnd = {
                        dragging = false
                        onScrubFinished()
                    },
                    onDragCancel = {
                        dragging = false
                        onScrubCancelled()
                    },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cy = size.height / 2f
            val stroke = trackHeight.toPx()
            val thumbX = fraction * size.width
            drawLine(
                color = Color.White.copy(alpha = 0.22f),
                start = Offset(0f, cy),
                end = Offset(size.width, cy),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            if (thumbX > 0f) {
                drawLine(
                    brush = Brush.horizontalGradient(listOf(accent, accentBright)),
                    start = Offset(0f, cy),
                    end = Offset(thumbX, cy),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
            if (duration > 0) {
                chapters.forEach { chapter ->
                    val chapterFraction = (chapter.startSeconds / duration).toFloat()
                    if (chapterFraction <= 0f || chapterFraction >= 1f) return@forEach
                    val x = chapterFraction * size.width
                    drawCircle(
                        color = if (x <= thumbX) Color.Black.copy(alpha = 0.45f)
                        else Color.White.copy(alpha = 0.85f),
                        radius = 1.6.dp.toPx(),
                        center = Offset(x, cy),
                    )
                }
            }
            val radius = thumbRadius.toPx()
            if (dragging) {
                drawCircle(Color.White.copy(alpha = 0.25f), radius * 2f, Offset(thumbX, cy))
            }
            drawCircle(Color.White, radius, Offset(thumbX, cy))
        }
        if (dragging) {
            val bubbleX = (fraction * barWidthPx - bubbleWidthPx / 2f)
                .coerceIn(0f, (barWidthPx - bubbleWidthPx).coerceAtLeast(0).toFloat())
                .roundToInt()
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset { IntOffset(bubbleX, -36.dp.roundToPx()) }
                    .onSizeChanged { bubbleWidthPx = it.width },
                color = Color(0xee1c1e22),
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    formatTime(position),
                    Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun LockedControlsButton(
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = Color(0xcc111111),
        shape = MaterialTheme.shapes.large,
    ) {
        IconButton(onClick = onUnlock) {
            Icon(
                Icons.Outlined.Lock,
                contentDescription = "Unlock player controls",
                tint = Color.White,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackSheet(viewModel: RelayViewModel, state: RelayUiState, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp)
                .navigationBarsPadding(),
        ) {
            Text("Audio & subtitles", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            Text("Audio", style = MaterialTheme.typography.titleMedium)
            state.tracks.filter { it.type == MpvTrack.Type.AUDIO }.forEach { track ->
                RadioSetting(track.label, track.selected) { viewModel.selectAudioTrack(track.id) }
            }
            DelayControl("Audio delay", state.mpvMetrics.audioDelaySeconds) { viewModel.adjustAudioDelay(it) }
            HorizontalDivider(Modifier.padding(vertical = 14.dp))
            Text("Subtitles", style = MaterialTheme.typography.titleMedium)
            RadioSetting("Off", state.tracks.none { it.type == MpvTrack.Type.SUBTITLE && it.selected }) {
                viewModel.selectSubtitleTrack(null)
            }
            state.tracks.filter { it.type == MpvTrack.Type.SUBTITLE }.forEach { track ->
                RadioSetting(track.label, track.selected) { viewModel.selectSubtitleTrack(track.id) }
            }
            DelayControl("Subtitle delay", state.mpvMetrics.subtitleDelaySeconds) { viewModel.adjustSubtitleDelay(it) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterSheet(viewModel: RelayViewModel, state: RelayUiState, onDismiss: () -> Unit) {
    val chapters = state.session?.chapters.orEmpty()
    val position = state.seekPreviewSeconds ?: state.mpvMetrics.positionSeconds
    val currentIndex = chapters.indexOfLast { it.startSeconds <= position }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp)
                .navigationBarsPadding(),
        ) {
            Text("Chapters", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            chapters.forEachIndexed { index, chapter ->
                val selected = index == currentIndex
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            viewModel.seekTo(chapter.startSeconds)
                            onDismiss()
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${index + 1}.",
                        Modifier.width(36.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        chapter.title ?: "Chapter ${index + 1}",
                        Modifier.weight(1f),
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        formatTime(chapter.startSeconds),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DelayControl(label: String, value: Double, adjust: (Double) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        OutlinedButton(onClick = { adjust(-0.1) }) { Text("−") }
        Text("${format(value)} s", Modifier.padding(horizontal = 14.dp))
        OutlinedButton(onClick = { adjust(0.1) }) { Text("+") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackSettingsSheet(viewModel: RelayViewModel, state: RelayUiState, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp)
                .navigationBarsPadding(),
        ) {
            Text("Playback settings", style = MaterialTheme.typography.headlineSmall)
            Text(
                if (state.directLocalFallback) "Changes apply to the next relay video."
                else "Model, quality, framing, and filter changes restart this video at the current position.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Text("Model", style = MaterialTheme.typography.titleMedium)
            LazyColumn(Modifier.heightIn(max = 220.dp)) {
                items(state.capabilities?.models.orEmpty(), key = { it.name }) { model ->
                    RadioSetting(model.name, state.selectedModel == model.name) { viewModel.setModel(model.name) }
                }
            }
            Text("Quality", style = MaterialTheme.typography.titleMedium)
            state.capabilities?.qualityOptions.orEmpty()
                .filter { it.androidSupported && it.id in RelaySessionController.ANDROID_HEVC_TIERS }
                .forEach { option ->
                    RadioSetting(option.label, state.qualityTier == option.id) {
                        viewModel.setQualityTier(option.id)
                    }
                }
            Text("Framing", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RelaySessionController.FIT_MODES.forEach { mode ->
                    FilterChip(
                        selected = state.fitMode == mode,
                        onClick = { viewModel.setFitMode(mode) },
                        label = { Text(mode.replaceFirstChar(Char::uppercase)) },
                    )
                }
            }
            Text("Downscale filter", style = MaterialTheme.typography.titleMedium)
            RadioSetting("Server default", state.resizeAlgorithm.isEmpty()) {
                viewModel.setResizeAlgorithm("")
            }
            state.capabilities?.resizeAlgorithms.orEmpty().forEach { algorithm ->
                RadioSetting(algorithm, state.resizeAlgorithm == algorithm) {
                    viewModel.setResizeAlgorithm(algorithm)
                }
            }
            SettingToggle("GPU deband", state.debandEnabled, viewModel::setDebandEnabled)
            SettingToggle("Touch gestures", state.gesturesEnabled, viewModel::setGesturesEnabled)
            SettingToggle("Diagnostic overlay", state.diagnosticsVisible, viewModel::setDiagnosticsVisible)
            VideoSyncPreferenceControls(viewModel, state)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ReconnectOverlay(
    status: ReconnectStatus,
    canFallback: Boolean,
    onFallback: () -> Unit,
    onCancel: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color(0x88000000)), contentAlignment = Alignment.Center) {
        Card(Modifier.fillMaxWidth(0.55f)) {
            // fillMaxWidth so the content centers in the card; a wrap-width
            // Column would hug the card's left edge.
            Column(
                Modifier.fillMaxWidth().padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(
                    status.reason,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (status.maxAttempts > 1) {
                        "Reconnecting — attempt ${status.attempt} of ${status.maxAttempts}. " +
                            "Playback resumes where it stopped."
                    } else {
                        "Restarting playback at the current position."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onCancel) { Text("Stop trying") }
                    if (canFallback) {
                        OutlinedButton(onClick = onFallback) { Text("Play original") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerError(
    message: String,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    canFallback: Boolean,
    onFallback: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color(0xaa000000)), contentAlignment = Alignment.Center) {
        Card(Modifier.fillMaxWidth(0.55f)) {
            Column(
                Modifier.fillMaxWidth().padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Playback failed", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                Text(message, textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onBack) { Text("Back to library") }
                    if (canFallback) {
                        OutlinedButton(onClick = onFallback) { Text("Play original") }
                    }
                    Button(onClick = onRetry) { Text("Reconnect") }
                }
            }
        }
    }
}

/** Compact one-line diagnostics summary shown under the player controls. */
private fun diagnosticsLine(state: RelayUiState): String {
    val mpv = state.mpvMetrics
    val transport = state.transportStats
    return "${state.sessionState}/${state.playerState}" +
        " · hwdec ${mpv.hardwareDecoder.ifEmpty { "pending" }}" +
        " · ${mpv.codedWidth}×${mpv.codedHeight}" +
        " · rx ${format(transport.averageMegabitsPerSecond)} Mbps" +
        " · cache ${mpv.cacheDurationMillis} ms" +
        " · drops ${mpv.outputDroppedFrames}/${mpv.decoderDroppedFrames}" +
        " · av ${format(mpv.avSyncSeconds * 1000)} ms"
}

private fun format(value: Double): String = String.format(Locale.US, "%.1f", value)

private fun formatTime(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0).toLong()
    val hours = total / 3600
    val minutes = total % 3600 / 60
    val remaining = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remaining)
    else "%d:%02d".format(minutes, remaining)
}
