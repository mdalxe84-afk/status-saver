package com.example.data

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "status_vault_prefs")

class PreferencesRepository(private val context: Context) {

    private object Keys {
        val WA_TREE_URI = stringPreferencesKey("wa_tree_uri")
        val WAB_TREE_URI = stringPreferencesKey("wab_tree_uri")
        val CUSTOM_TREE_URI = stringPreferencesKey("custom_tree_uri")
        val SOURCE_FILTER = stringPreferencesKey("source_filter")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val AUTO_SAVE_HISTORY = booleanPreferencesKey("auto_save_history_7d")
    }

    val connectedFolders: Flow<ConnectedFolders> = context.dataStore.data.map { prefs ->
        ConnectedFolders(
            whatsappUri = prefs[Keys.WA_TREE_URI]?.let { runCatching { Uri.parse(it) }.getOrNull() },
            whatsappBusinessUri = prefs[Keys.WAB_TREE_URI]?.let { runCatching { Uri.parse(it) }.getOrNull() },
            customUri = prefs[Keys.CUSTOM_TREE_URI]?.let { runCatching { Uri.parse(it) }.getOrNull() }
        )
    }

    val sourceFilter: Flow<SourceFilter> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.SOURCE_FILTER]
        SourceFilter.entries.firstOrNull { it.name == raw } ?: SourceFilter.ALL
    }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.THEME_MODE]
        ThemeMode.entries.firstOrNull { it.name == raw } ?: ThemeMode.SYSTEM
    }

    val autoSaveHistoryEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.AUTO_SAVE_HISTORY] ?: true
    }

    suspend fun setFolderUri(source: StatusSource, uri: Uri?) {
        val key = when (source) {
            StatusSource.WHATSAPP -> Keys.WA_TREE_URI
            StatusSource.WHATSAPP_BUSINESS -> Keys.WAB_TREE_URI
            StatusSource.CUSTOM_FOLDER -> Keys.CUSTOM_TREE_URI
            StatusSource.SAVED_VAULT, StatusSource.HISTORY_7D -> return
        }
        context.dataStore.edit { prefs ->
            if (uri == null) {
                prefs.remove(key)
            } else {
                prefs[key] = uri.toString()
            }
        }
    }

    suspend fun setSourceFilter(filter: SourceFilter) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SOURCE_FILTER] = filter.name
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { prefs ->
            prefs[Keys.THEME_MODE] = mode.name
        }
    }

    suspend fun setAutoSaveHistoryEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.AUTO_SAVE_HISTORY] = enabled
        }
    }
}
