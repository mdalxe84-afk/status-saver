package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.StatusSource
import com.example.ui.components.ConnectFolderOnboarding
import com.example.ui.components.DeleteConfirmDialog
import com.example.ui.components.EmptyVaultState
import com.example.ui.components.FoldersSettingsScreen
import com.example.ui.components.HistoryScreen
import com.example.ui.components.SourceFilterBar
import com.example.ui.components.StatusMediaGrid
import com.example.ui.components.StatusPreviewDialog
import com.example.ui.components.StatusTopAppBar
import kotlinx.coroutines.launch

private data class NavTabItem(
    val tab: VaultTab,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val badgeCount: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusVaultApp(viewModel: StatusVaultViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    var pendingFolderSource by remember { mutableStateOf<StatusSource?>(null) }
    var pendingSaveAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val source = pendingFolderSource
        pendingFolderSource = null
        if (source != null && uri != null) {
            viewModel.onFolderSelected(source, uri)
        }
    }

    val legacyWritePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pendingSaveAction
        pendingSaveAction = null
        if (granted) {
            action?.invoke()
        } else {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Storage permission is needed to save to gallery on Android 9")
            }
        }
    }

    fun launchFolderPicker(source: StatusSource) {
        pendingFolderSource = source
        folderPickerLauncher.launch(viewModel.buildInitialPickerUri(source))
    }

    fun runWithLegacyWriteCheck(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            action()
        } else {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                action()
            } else {
                pendingSaveAction = action
                legacyWritePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    // Refresh statuses automatically when returning to the app after viewing statuses in WhatsApp
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshAll()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is UiEffect.ShowMessage -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
                is UiEffect.LaunchIntent -> {
                    runCatching {
                        context.startActivity(effect.intent)
                    }.onFailure {
                        snackbarHostState.showSnackbar("No compatible app found to open this content")
                    }
                }
            }
        }
    }

    if (uiState.isSelectionMode && uiState.viewerState == null) {
        BackHandler {
            viewModel.clearSelection()
        }
    }

    val navItems = listOf(
        NavTabItem(
            tab = VaultTab.PHOTOS,
            labelRes = R.string.tab_photos,
            selectedIcon = Icons.Rounded.PhotoLibrary,
            unselectedIcon = Icons.Outlined.PhotoLibrary,
            badgeCount = uiState.visiblePhotos.size
        ),
        NavTabItem(
            tab = VaultTab.VIDEOS,
            labelRes = R.string.tab_videos,
            selectedIcon = Icons.Rounded.VideoLibrary,
            unselectedIcon = Icons.Outlined.VideoLibrary,
            badgeCount = uiState.visibleVideos.size
        ),
        NavTabItem(
            tab = VaultTab.HISTORY,
            labelRes = R.string.tab_history,
            selectedIcon = Icons.Rounded.History,
            unselectedIcon = Icons.Outlined.History,
            badgeCount = uiState.activeHistoryItems.size
        ),
        NavTabItem(
            tab = VaultTab.SAVED,
            labelRes = R.string.tab_saved,
            selectedIcon = Icons.Rounded.Bookmarks,
            unselectedIcon = Icons.Outlined.Bookmarks,
            badgeCount = uiState.savedItems.size
        ),
        NavTabItem(
            tab = VaultTab.FOLDERS,
            labelRes = R.string.tab_folders,
            selectedIcon = Icons.Rounded.FolderCopy,
            unselectedIcon = Icons.Outlined.FolderCopy,
            badgeCount = 0
        )
    )

    Box(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useNavRail = maxWidth >= 600.dp

            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets.safeDrawing,
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                topBar = {
                    val activeCount = when (uiState.selectedTab) {
                        VaultTab.PHOTOS -> uiState.visiblePhotos.size
                        VaultTab.VIDEOS -> uiState.visibleVideos.size
                        VaultTab.HISTORY -> uiState.visibleHistoryItems.size
                        VaultTab.SAVED -> uiState.savedItems.size
                        VaultTab.FOLDERS -> 0
                    }
                    StatusTopAppBar(
                        selectedTab = uiState.selectedTab,
                        isSelectionMode = uiState.isSelectionMode,
                        selectedCount = uiState.selectedIds.size,
                        itemCount = activeCount,
                        onRefresh = viewModel::refreshAll,
                        onClearSelection = viewModel::clearSelection,
                        onSelectAll = viewModel::selectAllInCurrentTab,
                        onSaveSelected = { runWithLegacyWriteCheck { viewModel.saveSelected() } },
                        onShareSelected = { viewModel.shareSelected() },
                        onDeleteSelected = viewModel::requestDeleteSelected
                    )
                },
                bottomBar = {
                    if (!useNavRail) {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface,
                            tonalElevation = 4.dp
                        ) {
                            navItems.forEach { navItem ->
                                val selected = uiState.selectedTab == navItem.tab
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { viewModel.selectTab(navItem.tab) },
                                    icon = {
                                        NavIconWithBadge(navItem = navItem, selected = selected)
                                    },
                                    label = {
                                        Text(
                                            text = stringResource(navItem.labelRes),
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                                    ),
                                    modifier = Modifier.testTag("nav_tab_${navItem.tab.name.lowercase()}")
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (useNavRail) {
                        NavigationRail(
                            containerColor = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxHeight()
                        ) {
                            Spacer(Modifier.weight(0.2f))
                            navItems.forEach { navItem ->
                                val selected = uiState.selectedTab == navItem.tab
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = { viewModel.selectTab(navItem.tab) },
                                    icon = {
                                        NavIconWithBadge(navItem = navItem, selected = selected)
                                    },
                                    label = {
                                        Text(
                                            text = stringResource(navItem.labelRes),
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    },
                                    colors = NavigationRailItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                                    ),
                                    modifier = Modifier.testTag("rail_tab_${navItem.tab.name.lowercase()}")
                                )
                            }
                            Spacer(Modifier.weight(1f))
                        }
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        when (uiState.selectedTab) {
                            VaultTab.PHOTOS, VaultTab.VIDEOS -> {
                                val items = if (uiState.selectedTab == VaultTab.PHOTOS) {
                                    uiState.visiblePhotos
                                } else {
                                    uiState.visibleVideos
                                }

                                if (!uiState.connectedFolders.hasAnyConnection) {
                                    ConnectFolderOnboarding(
                                        onConnectWhatsApp = { launchFolderPicker(StatusSource.WHATSAPP) },
                                        onConnectWhatsAppBusiness = { launchFolderPicker(StatusSource.WHATSAPP_BUSINESS) },
                                        onConnectCustomFolder = { launchFolderPicker(StatusSource.CUSTOM_FOLDER) }
                                    )
                                } else {
                                    Column(modifier = Modifier.fillMaxSize()) {
                                        SourceFilterBar(
                                            connectedFolders = uiState.connectedFolders,
                                            activeFilter = uiState.sourceFilter,
                                            onSelectFilter = viewModel::selectSourceFilter
                                        )

                                        PullToRefreshBox(
                                            isRefreshing = uiState.isLoading,
                                            onRefresh = viewModel::refreshAll,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .weight(1f)
                                        ) {
                                            if (items.isEmpty() && !uiState.isLoading) {
                                                EmptyVaultState(
                                                    isSavedTab = false,
                                                    isVideoTab = uiState.selectedTab == VaultTab.VIDEOS,
                                                    isWhatsAppInstalled = uiState.isWhatsAppInstalled,
                                                    onRefresh = viewModel::refreshAll,
                                                    onOpenWhatsApp = viewModel::openWhatsApp,
                                                    onManageFolders = { viewModel.selectTab(VaultTab.FOLDERS) },
                                                    onBrowsePhotos = { viewModel.selectTab(VaultTab.PHOTOS) }
                                                )
                                            } else {
                                                StatusMediaGrid(
                                                    items = items,
                                                    isSavedTab = false,
                                                    selectedIds = uiState.selectedIds,
                                                    isSelectionMode = uiState.isSelectionMode,
                                                    onItemClick = { idx -> viewModel.openViewer(items, idx) },
                                                    onItemLongClick = { item -> viewModel.toggleSelection(item.id) },
                                                    onSaveClick = { item ->
                                                        runWithLegacyWriteCheck { viewModel.saveSingle(item) }
                                                    },
                                                    onShareClick = { item -> viewModel.shareItems(listOf(item)) },
                                                    onDeleteClick = {}
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            VaultTab.HISTORY -> {
                                PullToRefreshBox(
                                    isRefreshing = uiState.isLoading,
                                    onRefresh = viewModel::refreshAll,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    HistoryScreen(
                                        items = uiState.visibleHistoryItems,
                                        totalActiveCount = uiState.activeHistoryItems.size,
                                        mediaFilter = uiState.historyMediaFilter,
                                        autoSaveEnabled = uiState.autoSaveHistoryEnabled,
                                        hasConnectedFolder = uiState.connectedFolders.hasAnyConnection,
                                        nowMillis = uiState.nowMillis,
                                        selectedIds = uiState.selectedIds,
                                        isSelectionMode = uiState.isSelectionMode,
                                        onSelectFilter = viewModel::selectHistoryMediaFilter,
                                        onEnableAutoSave = { viewModel.setAutoSaveHistoryEnabled(true) },
                                        onItemClick = { idx ->
                                            viewModel.openViewer(uiState.visibleHistoryItems, idx)
                                        },
                                        onItemLongClick = { item -> viewModel.toggleSelection(item.id) },
                                        onDownloadClick = { item ->
                                            runWithLegacyWriteCheck { viewModel.saveSingle(item) }
                                        },
                                        onShareClick = { item -> viewModel.shareItems(listOf(item)) },
                                        onDeleteClick = { item -> viewModel.requestDelete(listOf(item)) },
                                        onRefresh = viewModel::refreshAll,
                                        onOpenFolders = { viewModel.selectTab(VaultTab.FOLDERS) }
                                    )
                                }
                            }

                            VaultTab.SAVED -> {
                                PullToRefreshBox(
                                    isRefreshing = uiState.isLoading,
                                    onRefresh = viewModel::refreshAll,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    if (uiState.savedItems.isEmpty() && !uiState.isLoading) {
                                        EmptyVaultState(
                                            isSavedTab = true,
                                            isVideoTab = false,
                                            isWhatsAppInstalled = uiState.isWhatsAppInstalled,
                                            onRefresh = viewModel::refreshAll,
                                            onOpenWhatsApp = viewModel::openWhatsApp,
                                            onManageFolders = { viewModel.selectTab(VaultTab.FOLDERS) },
                                            onBrowsePhotos = { viewModel.selectTab(VaultTab.PHOTOS) }
                                        )
                                    } else {
                                        StatusMediaGrid(
                                            items = uiState.savedItems,
                                            isSavedTab = true,
                                            selectedIds = uiState.selectedIds,
                                            isSelectionMode = uiState.isSelectionMode,
                                            onItemClick = { idx ->
                                                viewModel.openViewer(uiState.savedItems, idx)
                                            },
                                            onItemLongClick = { item -> viewModel.toggleSelection(item.id) },
                                            onSaveClick = {},
                                            onShareClick = { item -> viewModel.shareItems(listOf(item)) },
                                            onDeleteClick = { item -> viewModel.requestDelete(listOf(item)) }
                                        )
                                    }
                                }
                            }

                            VaultTab.FOLDERS -> {
                                FoldersSettingsScreen(
                                    connectedFolders = uiState.connectedFolders,
                                    themeMode = uiState.themeMode,
                                    autoSaveHistoryEnabled = uiState.autoSaveHistoryEnabled,
                                    historyCount = uiState.activeHistoryItems.size,
                                    savedCount = uiState.savedItems.size,
                                    liveCount = uiState.allLiveStatuses.size,
                                    isWhatsAppInstalled = uiState.isWhatsAppInstalled || uiState.isWhatsAppBusinessInstalled,
                                    onConnectSource = ::launchFolderPicker,
                                    onDisconnectSource = viewModel::disconnectFolder,
                                    onThemeChange = viewModel::setThemeMode,
                                    onToggleAutoSaveHistory = viewModel::setAutoSaveHistoryEnabled,
                                    onClearHistory = viewModel::requestClearAllHistory,
                                    onOpenWhatsApp = viewModel::openWhatsApp
                                )
                            }
                        }

                        if (uiState.isActionBusy) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }
        }

        // Full-Screen Swipeable Status Preview Dialog
        val pendingIsHistory = uiState.pendingDeleteItems?.all { it.isHistoryItem } == true
        uiState.viewerState?.let { viewer ->
            StatusPreviewDialog(
                viewerState = viewer,
                nowMillis = uiState.nowMillis,
                pendingDeleteCount = uiState.pendingDeleteItems?.size,
                pendingDeleteIsHistory = pendingIsHistory,
                onDismissDeleteDialog = viewModel::dismissDeleteDialog,
                onConfirmDelete = viewModel::confirmDelete,
                onClose = viewModel::closeViewer,
                onSave = { item ->
                    runWithLegacyWriteCheck { viewModel.saveSingle(item) }
                },
                onShare = { item -> viewModel.shareItems(listOf(item)) },
                onRepost = { item ->
                    val targetPkg = when (item.source) {
                        StatusSource.WHATSAPP_BUSINESS -> "com.whatsapp.w4b"
                        else -> "com.whatsapp"
                    }
                    viewModel.shareItems(listOf(item), targetPackage = targetPkg)
                },
                onDelete = { item -> viewModel.requestDelete(listOf(item)) }
            )
        } ?: uiState.pendingDeleteItems?.let { pending ->
            DeleteConfirmDialog(
                count = pending.size,
                isHistoryDelete = pendingIsHistory,
                onDismiss = viewModel::dismissDeleteDialog,
                onConfirm = viewModel::confirmDelete
            )
        }

        if (uiState.pendingClearHistory) {
            AlertDialog(
                onDismissRequest = viewModel::dismissClearHistoryDialog,
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.DeleteSweep,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                },
                title = { Text("Clear 7-Day History?") },
                text = {
                    Text("This removes all cached items from your 7-day history. Statuses already downloaded to DCIM/StatusVault remain untouched.")
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissClearHistoryDialog) {
                        Text(stringResource(R.string.cancel))
                    }
                },
                confirmButton = {
                    Button(
                        onClick = viewModel::confirmClearAllHistory,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        modifier = Modifier.testTag("confirm_clear_history_button")
                    ) {
                        Text("Clear All")
                    }
                }
            )
        }
    }
}

@Composable
private fun NavIconWithBadge(
    navItem: NavTabItem,
    selected: Boolean
) {
    val iconVector = if (selected) navItem.selectedIcon else navItem.unselectedIcon
    if (navItem.badgeCount > 0) {
        BadgedBox(
            badge = {
                Badge(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Text(
                        text = if (navItem.badgeCount > 99) "99+" else navItem.badgeCount.toString()
                    )
                }
            }
        ) {
            Icon(
                imageVector = iconVector,
                contentDescription = null
            )
        }
    } else {
        Icon(
            imageVector = iconVector,
            contentDescription = null
        )
    }
}
