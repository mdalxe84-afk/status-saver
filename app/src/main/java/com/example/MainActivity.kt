package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.Coil
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import com.example.data.ThemeMode
import com.example.ui.StatusVaultApp
import com.example.ui.StatusVaultViewModel
import com.example.ui.theme.StatusVaultTheme

class MainActivity : ComponentActivity() {

    private val viewModel: StatusVaultViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable video frame thumbnail decoding in Coil for video statuses
        val imageLoader = ImageLoader.Builder(applicationContext)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .crossfade(true)
            .build()
        Coil.setImageLoader(imageLoader)

        enableEdgeToEdge()

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val darkTheme = when (uiState.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            StatusVaultTheme(darkTheme = darkTheme) {
                StatusVaultApp(viewModel = viewModel)
            }
        }
    }
}
