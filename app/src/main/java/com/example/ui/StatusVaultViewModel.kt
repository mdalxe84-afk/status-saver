package com.example.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ConnectedFolders
import com.example.data.HistoryMediaFilter
import com.example.data.MediaType
import com.example.data.PreferencesRepository
import com.example.data.SaveResult
import com.example.data.SourceFilter
import com.example.data.StatusItem
import com.example.data.StatusRepository
import com.example.data.StatusSource
import com.example.data.ThemeMode
import com.example.data.history.HistoryRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class VaultTab {
    PHOTOS,
    VIDEOS,
    HISTORY,
    SAVED,
    FOLDERS
}

data class ViewerState(
    val items: List<StatusItem>,
    val initialIndex: Int
)

data class StatusVaultUiState(
    val selectedTab: VaultTab = VaultTab.PHOTOS,
    val sourceFilter: SourceFilter = SourceFilter.ALL,
    val historyMediaFilter: HistoryMediaFilter = HistoryMediaFilter.ALL,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val autoSaveHistoryEnabled: Boolean = true,
    val connectedFolders: ConnectedFolders = ConnectedFolders(),
    val allLiveStatuses: List<StatusItem> = emptyList(),
    val rawHistoryItems: List<StatusItem> = emptyList(),
    val savedItems: List<StatusItem> = emptyList(),
    val nowMillis: Long = System.currentTimeMillis(),
    val isLoading: Boolean = true,
    val isActionBusy: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val viewerState: ViewerState? = null,
    val pendingDeleteItems: List<StatusItem>? = null,
    val pendingClearHistory: Boolean = false,
    val isWhatsAppInstalled: Boolean = false,
    val isWhatsAppBusinessInstalled: Boolean = false
) {
    val isSelectionMode: Boolean
        get() = selectedIds.isNotEmpty()

    private val filteredLiveStatuses: List<StatusItem>
        get() = when (sourceFilter) {
            SourceFilter.ALL -> allLiveStatuses
            SourceFilter.WHATSAPP -> allLiveStatuses.filter { it.source == StatusSource.WHATSAPP }
            SourceFilter.WHATSAPP_BUSINESS -> allLiveStatuses.filter { it.source == StatusSource.WHATSAPP_BUSINESS }
            SourceFilter.CUSTOM_FOLDER -> allLiveStatuses.filter { it.source == StatusSource.CUSTOM_FOLDER }
        }

    val visiblePhotos: List<StatusItem>
        get() = filteredLiveStatuses.filter { it.mediaType == MediaType.IMAGE }

    val visibleVideos: List<StatusItem>
        get() = filteredLiveStatuses.filter { it.mediaType == MediaType.VIDEO }

    val activeHistoryItems: List<StatusItem>
        get() {
            val savedNames = savedItems.map { it.name }.toSet()
            return rawHistoryItems
                .filter { (it.expiresAtMillis ?: Long.MAX_VALUE) > nowMillis }
                .map { it.copy(isSavedInVault = savedNames.contains(it.name)) }
        }

    val visibleHistoryItems: List<StatusItem>
        get() = when (historyMediaFilter) {
            HistoryMediaFilter.ALL -> activeHistoryItems
            HistoryMediaFilter.IMAGES -> activeHistoryItems.filter { it.mediaType == MediaType.IMAGE }
            HistoryMediaFilter.VIDEOS -> activeHistoryItems.filter { it.mediaType == MediaType.VIDEO }
        }

    val currentTabItems: List<StatusItem>
        get() = when (selectedTab) {
            VaultTab.PHOTOS -> visiblePhotos
            VaultTab.VIDEOS -> visibleVideos
            VaultTab.HISTORY -> visibleHistoryItems
            VaultTab.SAVED -> savedItems
            VaultTab.FOLDERS -> emptyList()
        }
}

sealed interface UiEffect {
    data class ShowMessage(val message: String) : UiEffect
    data class LaunchIntent(val intent: Intent) : UiEffect
}

class StatusVaultViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PreferencesRepository(application)
    private val repository = StatusRepository(application)
    private val historyRepository = HistoryRepository(application)

    private val _uiState = MutableStateFlow(
        StatusVaultUiState(
            isWhatsAppInstalled = repository.isPackageInstalled("com.whatsapp"),
            isWhatsAppBusinessInstalled = repository.isPackageInstalled("com.whatsapp.w4b")
        )
    )
    val uiState: StateFlow<StatusVaultUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<UiEffect>()
    val effects: SharedFlow<UiEffect> = _effects.asSharedFlow()

    init {
        viewModelScope.launch {
            historyRepository.purgeExpired()
        }

        viewModelScope.launch {
            historyRepository.observeHistoryItems(emptySet()).collect { items ->
                _uiState.update { state ->
                    state.copy(rawHistoryItems = items)
                }
            }
        }

        // Update remaining time labels and auto-purge expired 7-day items every minute
        viewModelScope.launch {
            while (isActive) {
                delay(60_000L)
                val now = System.currentTimeMillis()
                historyRepository.purgeExpired(now)
                _uiState.update { it.copy(nowMillis = now) }
            }
        }

        viewModelScope.launch {
            combine(
                prefs.connectedFolders,
                prefs.sourceFilter,
                prefs.themeMode,
                prefs.autoSaveHistoryEnabled
            ) { folders, filter, theme, autoHistory ->
                Quad(folders, filter, theme, autoHistory)
            }.collect { (folders, filter, theme, autoHistory) ->
                _uiState.update {
                    it.copy(
                        connectedFolders = folders,
                        sourceFilter = filter,
                        themeMode = theme,
                        autoSaveHistoryEnabled = autoHistory
                    )
                }
                refreshStatuses(folders, autoHistory)
            }
        }
    }

    private data class Quad<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
    )

    fun refreshAll() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    nowMillis = System.currentTimeMillis(),
                    isWhatsAppInstalled = repository.isPackageInstalled("com.whatsapp"),
                    isWhatsAppBusinessInstalled = repository.isPackageInstalled("com.whatsapp.w4b")
                )
            }
            refreshStatuses(
                folders = _uiState.value.connectedFolders,
                autoHistory = _uiState.value.autoSaveHistoryEnabled
            )
        }
    }

    private suspend fun refreshStatuses(
        folders: ConnectedFolders,
        autoHistory: Boolean = _uiState.value.autoSaveHistoryEnabled
    ) {
        val now = System.currentTimeMillis()
        historyRepository.purgeExpired(now)

        val saved = repository.loadSavedVaultItems()
        val savedNames = saved.map { it.name }.toSet()
        val live = if (folders.hasAnyConnection) {
            repository.loadLiveStatuses(folders, savedNames)
        } else {
            emptyList()
        }

        if (autoHistory && live.isNotEmpty()) {
            historyRepository.syncViewedStatuses(live)
        }

        _uiState.update { state ->
            val updatedViewer = state.viewerState?.let { viewer ->
                val sourcePool = when (state.selectedTab) {
                    VaultTab.SAVED -> saved
                    VaultTab.HISTORY -> state.activeHistoryItems
                    else -> live
                }
                val updatedItems = viewer.items.mapNotNull { old ->
                    sourcePool.firstOrNull { it.id == old.id } ?: old.copy(
                        isSavedInVault = savedNames.contains(old.name)
                    )
                }
                if (updatedItems.isEmpty()) null
                else viewer.copy(
                    items = updatedItems,
                    initialIndex = viewer.initialIndex.coerceIn(0, updatedItems.lastIndex)
                )
            }

            state.copy(
                allLiveStatuses = live,
                savedItems = saved,
                nowMillis = now,
                isLoading = false,
                viewerState = updatedViewer
            )
        }
    }

    fun buildInitialPickerUri(source: StatusSource): Uri? {
        return repository.buildInitialPickerUri(source)
    }

    fun onFolderSelected(source: StatusSource, uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val persisted = repository.persistTreePermission(uri)
            if (persisted) {
                prefs.setFolderUri(source, uri)
                _effects.emit(UiEffect.ShowMessage("${source.displayName} folder connected"))
            } else {
                _effects.emit(UiEffect.ShowMessage("Couldn't persist folder permission. Please try again."))
            }
        }
    }

    fun disconnectFolder(source: StatusSource) {
        viewModelScope.launch {
            val currentUri = when (source) {
                StatusSource.WHATSAPP -> _uiState.value.connectedFolders.whatsappUri
                StatusSource.WHATSAPP_BUSINESS -> _uiState.value.connectedFolders.whatsappBusinessUri
                StatusSource.CUSTOM_FOLDER -> _uiState.value.connectedFolders.customUri
                StatusSource.SAVED_VAULT, StatusSource.HISTORY_7D -> null
            }
            currentUri?.let { repository.releaseTreePermission(it) }
            prefs.setFolderUri(source, null)
            if (_uiState.value.sourceFilter.name == source.name) {
                prefs.setSourceFilter(SourceFilter.ALL)
            }
            _effects.emit(UiEffect.ShowMessage("${source.displayName} folder disconnected"))
        }
    }

    fun selectTab(tab: VaultTab) {
        _uiState.update {
            it.copy(
                selectedTab = tab,
                selectedIds = emptySet()
            )
        }
    }

    fun selectSourceFilter(filter: SourceFilter) {
        viewModelScope.launch {
            prefs.setSourceFilter(filter)
            _uiState.update { it.copy(selectedIds = emptySet()) }
        }
    }

    fun selectHistoryMediaFilter(filter: HistoryMediaFilter) {
        _uiState.update {
            it.copy(
                historyMediaFilter = filter,
                selectedIds = emptySet()
            )
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            prefs.setThemeMode(mode)
        }
    }

    fun setAutoSaveHistoryEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setAutoSaveHistoryEnabled(enabled)
            if (enabled) {
                val live = _uiState.value.allLiveStatuses
                if (live.isNotEmpty()) {
                    val archived = historyRepository.syncViewedStatuses(live)
                    if (archived > 0) {
                        _effects.emit(UiEffect.ShowMessage("Auto-saved $archived viewed status${if (archived == 1) "" else "es"} to 7-Day History"))
                    }
                }
            }
        }
    }

    fun toggleSelection(id: String) {
        _uiState.update { state ->
            val next = if (id in state.selectedIds) {
                state.selectedIds - id
            } else {
                state.selectedIds + id
            }
            state.copy(selectedIds = next)
        }
    }

    fun selectAllInCurrentTab() {
        val ids = _uiState.value.currentTabItems.map { it.id }.toSet()
        _uiState.update { state ->
            val next = if (state.selectedIds.size == ids.size) emptySet() else ids
            state.copy(selectedIds = next)
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedIds = emptySet()) }
    }

    fun saveSingle(item: StatusItem) {
        viewModelScope.launch {
            _uiState.update { it.copy(isActionBusy = true) }
            val result = repository.copyStatusToPublicDcimFolder(item)
            refreshStatuses(_uiState.value.connectedFolders)
            _uiState.update { it.copy(isActionBusy = false) }
            val msg = when (result) {
                is SaveResult.Saved -> "Saved to DCIM/StatusVault"
                is SaveResult.AlreadyExists -> "Already in DCIM/StatusVault"
                is SaveResult.Failure -> result.reason
            }
            _effects.emit(UiEffect.ShowMessage(msg))
        }
    }

    fun saveSelected() {
        val selected = _uiState.value.currentTabItems.filter { it.id in _uiState.value.selectedIds }
        if (selected.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isActionBusy = true) }
            val result = repository.saveBatch(selected)
            refreshStatuses(_uiState.value.connectedFolders)
            _uiState.update { it.copy(isActionBusy = false, selectedIds = emptySet()) }

            val message = buildString {
                if (result.newlySavedCount > 0) {
                    append("Saved ${result.newlySavedCount} status${if (result.newlySavedCount == 1) "" else "es"}")
                }
                if (result.alreadySavedCount > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("${result.alreadySavedCount} already saved")
                }
                if (result.failedCount > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("${result.failedCount} failed")
                }
            }.ifBlank { "Nothing saved" }

            _effects.emit(UiEffect.ShowMessage(message))
        }
    }

    fun requestDelete(items: List<StatusItem>) {
        if (items.isEmpty()) return
        _uiState.update { it.copy(pendingDeleteItems = items) }
    }

    fun requestDeleteSelected() {
        val selected = _uiState.value.currentTabItems.filter { it.id in _uiState.value.selectedIds }
        requestDelete(selected)
    }

    fun dismissDeleteDialog() {
        _uiState.update { it.copy(pendingDeleteItems = null) }
    }

    fun confirmDelete() {
        val targets = _uiState.value.pendingDeleteItems ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(pendingDeleteItems = null, isActionBusy = true) }

            val savedTargets = targets.filter { it.source == StatusSource.SAVED_VAULT }
            val historyTargets = targets.filter { it.source == StatusSource.HISTORY_7D }

            var deleted = 0
            if (savedTargets.isNotEmpty()) {
                deleted += repository.deleteSavedItems(savedTargets)
            }
            if (historyTargets.isNotEmpty()) {
                deleted += historyRepository.deleteHistoryItems(historyTargets)
            }

            val deletedIds = targets.map { it.id }.toSet()

            _uiState.update { state ->
                val nextViewer = state.viewerState?.let { viewer ->
                    val remaining = viewer.items.filterNot { it.id in deletedIds }
                    if (remaining.isEmpty()) null
                    else viewer.copy(
                        items = remaining,
                        initialIndex = viewer.initialIndex.coerceIn(0, remaining.lastIndex)
                    )
                }
                state.copy(
                    selectedIds = emptySet(),
                    viewerState = nextViewer
                )
            }

            refreshStatuses(_uiState.value.connectedFolders)
            _uiState.update { it.copy(isActionBusy = false) }

            val msg = if (deleted > 0) {
                "Deleted $deleted status${if (deleted == 1) "" else "es"}"
            } else {
                "Unable to delete item"
            }
            _effects.emit(UiEffect.ShowMessage(msg))
        }
    }

    fun requestClearAllHistory() {
        _uiState.update { it.copy(pendingClearHistory = true) }
    }

    fun dismissClearHistoryDialog() {
        _uiState.update { it.copy(pendingClearHistory = false) }
    }

    fun confirmClearAllHistory() {
        viewModelScope.launch {
            _uiState.update { it.copy(pendingClearHistory = false, isActionBusy = true) }
            historyRepository.clearAllHistory()
            _uiState.update { it.copy(isActionBusy = false, selectedIds = emptySet()) }
            _effects.emit(UiEffect.ShowMessage("7-Day History cleared"))
        }
    }

    fun shareItems(items: List<StatusItem>, targetPackage: String? = null) {
        viewModelScope.launch {
            val intent = repository.buildShareIntent(items, targetPackage)
            if (intent != null) {
                _effects.emit(UiEffect.LaunchIntent(intent))
                clearSelection()
            } else {
                _effects.emit(UiEffect.ShowMessage("Nothing to share"))
            }
        }
    }

    fun shareSelected(targetPackage: String? = null) {
        val selected = _uiState.value.currentTabItems.filter { it.id in _uiState.value.selectedIds }
        shareItems(selected, targetPackage)
    }

    fun openWhatsApp() {
        viewModelScope.launch {
            val intent = repository.openWhatsAppApp()
            if (intent != null) {
                _effects.emit(UiEffect.LaunchIntent(intent))
            } else {
                _effects.emit(UiEffect.ShowMessage("WhatsApp is not installed on this device"))
            }
        }
    }

    fun openViewer(items: List<StatusItem>, startIndex: Int) {
        if (items.isEmpty()) return
        _uiState.update {
            it.copy(
                viewerState = ViewerState(
                    items = items,
                    initialIndex = startIndex.coerceIn(0, items.lastIndex)
                )
            )
        }
    }

    fun closeViewer() {
        _uiState.update { it.copy(viewerState = null) }
    }
}
