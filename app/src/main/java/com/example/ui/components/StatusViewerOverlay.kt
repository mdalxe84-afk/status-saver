package com.example.ui.components

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.data.MediaType
import com.example.data.StatusItem
import com.example.data.StatusSource
import com.example.ui.ViewerState
import com.example.ui.theme.StatusSavedGreen
import java.text.DateFormat
import java.util.Date

@Composable
fun StatusPreviewDialog(
    viewerState: ViewerState,
    nowMillis: Long,
    pendingDeleteCount: Int?,
    pendingDeleteIsHistory: Boolean = false,
    onDismissDeleteDialog: () -> Unit,
    onConfirmDelete: () -> Unit,
    onClose: () -> Unit,
    onSave: (StatusItem) -> Unit,
    onShare: (StatusItem) -> Unit,
    onRepost: (StatusItem) -> Unit,
    onDelete: (StatusItem) -> Unit
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.let { window ->
                WindowCompat.setDecorFitsSystemWindows(window, false)
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
        }

        val items = viewerState.items
        val pagerState = rememberPagerState(
            initialPage = viewerState.initialIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
            pageCount = { items.size }
        )
        val currentItem = items.getOrNull(pagerState.currentPage) ?: return@Dialog
        var isMuted by remember { mutableStateOf(false) }
        var showChrome by remember { mutableStateOf(true) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF070B09))
                .testTag("status_preview_dialog")
        ) {
            HorizontalPager(
                state = pagerState,
                key = { index -> items[index].id },
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val item = items[page]
                val isActivePage = pagerState.currentPage == page
                if (item.mediaType == MediaType.VIDEO) {
                    VideoStatusPage(
                        uri = item.uri,
                        isActivePage = isActivePage,
                        isMuted = isMuted
                    )
                } else {
                    ZoomableImagePage(
                        uri = item.uri,
                        contentDescription = item.name,
                        onSingleTap = { showChrome = !showChrome }
                    )
                }
            }

            // Top Bar Scrim + Controls
            AnimatedVisibility(
                visible = showChrome || currentItem.mediaType == MediaType.VIDEO,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Black.copy(alpha = 0.74f), Color.Transparent)
                            )
                        )
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.testTag("viewer_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint = Color.White
                        )
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 6.dp)
                    ) {
                        Text(
                            text = currentItem.originSource?.displayName ?: currentItem.source.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val dateLabel = remember(currentItem.lastModifiedMillis) {
                            if (currentItem.lastModifiedMillis > 0L) {
                                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                    .format(Date(currentItem.lastModifiedMillis))
                            } else {
                                ""
                            }
                        }
                        val remainingLabel = remember(currentItem.expiresAtMillis, nowMillis) {
                            currentItem.formattedRemainingDuration(nowMillis)
                        }
                        Text(
                            text = listOfNotNull(
                                currentItem.formattedSize(),
                                dateLabel.takeIf { it.isNotBlank() },
                                remainingLabel
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.78f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (currentItem.mediaType == MediaType.VIDEO) {
                        IconButton(
                            onClick = { isMuted = !isMuted },
                            modifier = Modifier.testTag("viewer_mute_button")
                        ) {
                            Icon(
                                imageVector = if (isMuted) {
                                    Icons.AutoMirrored.Rounded.VolumeOff
                                } else {
                                    Icons.AutoMirrored.Rounded.VolumeUp
                                },
                                contentDescription = if (isMuted) "Unmute" else "Mute",
                                tint = Color.White
                            )
                        }
                    }

                    Surface(
                        color = Color.White.copy(alpha = 0.16f),
                        shape = CircleShape,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text(
                            text = "${pagerState.currentPage + 1} / ${items.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            // Bottom Floating Action Bar
            AnimatedVisibility(
                visible = showChrome || currentItem.mediaType == MediaType.VIDEO,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.80f))
                            )
                        )
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        color = Color(0xFF17211D).copy(alpha = 0.94f),
                        shape = RoundedCornerShape(28.dp),
                        tonalElevation = 6.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (currentItem.source == StatusSource.SAVED_VAULT) {
                                ViewerPillAction(
                                    icon = Icons.Rounded.DeleteOutline,
                                    label = stringResource(R.string.action_delete),
                                    containerColor = Color(0xFF4A1F1F),
                                    contentColor = Color(0xFFFFB4AB),
                                    testTag = "viewer_delete_button",
                                    onClick = { onDelete(currentItem) }
                                )
                                ViewerPillAction(
                                    icon = Icons.Rounded.Repeat,
                                    label = stringResource(R.string.action_repost),
                                    containerColor = Color.White.copy(alpha = 0.14f),
                                    contentColor = Color.White,
                                    testTag = "viewer_repost_button",
                                    onClick = { onRepost(currentItem) }
                                )
                                ViewerPillAction(
                                    icon = Icons.Rounded.Share,
                                    label = stringResource(R.string.action_share),
                                    containerColor = Color.White.copy(alpha = 0.14f),
                                    contentColor = Color.White,
                                    testTag = "viewer_share_button",
                                    onClick = { onShare(currentItem) }
                                )
                            } else if (currentItem.source == StatusSource.HISTORY_7D) {
                                val isSaved = currentItem.isSavedInVault
                                ViewerPillAction(
                                    icon = if (isSaved) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
                                    label = stringResource(if (isSaved) R.string.action_saved else R.string.action_download),
                                    containerColor = if (isSaved) StatusSavedGreen else MaterialTheme.colorScheme.primary,
                                    contentColor = Color.White,
                                    testTag = "viewer_save_button",
                                    onClick = { onSave(currentItem) }
                                )
                                ViewerPillAction(
                                    icon = Icons.Rounded.Share,
                                    label = stringResource(R.string.action_share),
                                    containerColor = Color.White.copy(alpha = 0.14f),
                                    contentColor = Color.White,
                                    testTag = "viewer_share_button",
                                    onClick = { onShare(currentItem) }
                                )
                                ViewerPillAction(
                                    icon = Icons.Rounded.DeleteOutline,
                                    label = stringResource(R.string.action_delete),
                                    containerColor = Color(0xFF4A1F1F),
                                    contentColor = Color(0xFFFFB4AB),
                                    testTag = "viewer_delete_button",
                                    onClick = { onDelete(currentItem) }
                                )
                            } else {
                                val isSaved = currentItem.isSavedInVault
                                ViewerPillAction(
                                    icon = if (isSaved) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
                                    label = stringResource(if (isSaved) R.string.action_saved else R.string.action_save),
                                    containerColor = if (isSaved) StatusSavedGreen else MaterialTheme.colorScheme.primary,
                                    contentColor = Color.White,
                                    testTag = "viewer_save_button",
                                    onClick = { onSave(currentItem) }
                                )
                                ViewerPillAction(
                                    icon = Icons.Rounded.Repeat,
                                    label = stringResource(R.string.action_repost),
                                    containerColor = Color.White.copy(alpha = 0.14f),
                                    contentColor = Color.White,
                                    testTag = "viewer_repost_button",
                                    onClick = { onRepost(currentItem) }
                                )
                                ViewerPillAction(
                                    icon = Icons.Rounded.Share,
                                    label = stringResource(R.string.action_share),
                                    containerColor = Color.White.copy(alpha = 0.14f),
                                    contentColor = Color.White,
                                    testTag = "viewer_share_button",
                                    onClick = { onShare(currentItem) }
                                )
                            }
                        }
                    }
                }
            }

            if (pendingDeleteCount != null) {
                DeleteConfirmDialog(
                    count = pendingDeleteCount,
                    isHistoryDelete = pendingDeleteIsHistory,
                    onDismiss = onDismissDeleteDialog,
                    onConfirm = onConfirmDelete
                )
            }
        }
    }
}

@Composable
private fun ViewerPillAction(
    icon: ImageVector,
    label: String,
    containerColor: Color,
    contentColor: Color,
    testTag: String,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        modifier = Modifier.testTag(testTag)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun ZoomableImagePage(
    uri: Uri,
    contentDescription: String,
    onSingleTap: () -> Unit
) {
    val context = LocalContext.current
    var scale by remember(uri) { mutableFloatStateOf(1f) }
    var offset by remember(uri) { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(uri) {
                detectTapGestures(
                    onTap = { onSingleTap() },
                    onDoubleTap = {
                        if (scale > 1.1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.4f
                        }
                    }
                )
            }
            .pointerInput(uri) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val nextScale = (scale * zoom).coerceIn(1f, 4f)
                    scale = nextScale
                    if (nextScale > 1f) {
                        val maxOffsetX = (size.width * (nextScale - 1f)) / 2f
                        val maxOffsetY = (size.height * (nextScale - 1f)) / 2f
                        offset = Offset(
                            x = (offset.x + pan.x).coerceIn(-maxOffsetX, maxOffsetX),
                            y = (offset.y + pan.y).coerceIn(-maxOffsetY, maxOffsetY)
                        )
                    } else {
                        offset = Offset.Zero
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(uri)
                .crossfade(true)
                .build(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                )
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoStatusPage(
    uri: Uri,
    isActivePage: Boolean,
    isMuted: Boolean
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
        }
    }

    LaunchedEffect(isActivePage) {
        exoPlayer.playWhenReady = isActivePage
        if (!isActivePage) {
            exoPlayer.pause()
        }
    }

    LaunchedEffect(isMuted) {
        exoPlayer.volume = if (isMuted) 0f else 1f
    }

    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> exoPlayer.pause()
                Lifecycle.Event.ON_RESUME -> if (isActivePage) exoPlayer.play()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                    controllerShowTimeoutMs = 2500
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { view ->
                if (view.player != exoPlayer) {
                    view.player = exoPlayer
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 56.dp, bottom = 88.dp)
                .testTag("exo_player_view")
        )
    }
}
