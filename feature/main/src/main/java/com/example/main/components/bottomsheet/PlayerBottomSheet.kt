package com.example.main.components.bottomsheet

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.util.trace
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.domain.model.playable.PlayerMode
import com.example.domain.model.playable.PlayableItem
import com.example.domain.model.playable.supportedPlayerModes
import com.example.main.MainViewModel
import com.example.main.R
import com.example.main.components.bottomsheet.GraphicsLayerConstants.PEEK_HEIGHT
import com.example.main.components.bottomsheet.item.PlayerBottomSheetHeader
import com.example.main.components.bottomsheet.item.PlayerLoadingIndicator
import com.example.main.components.bottomsheet.item.PlayerThumbnailView
import com.example.main.components.bottomsheet.item.PlaylistFloatingButton
import com.example.main.components.bottomsheet.item.VideoDetailPanel
import com.example.main.components.bottomsheet.state.VideoDetailUiState
import com.example.main.components.karaoke.KaraokeOverlay
import com.example.util.constants.AppColors
import com.example.util.ToastUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.pow
import kotlin.math.roundToInt


object GraphicsLayerConstants {
    const val FULLY_EXPANDED = 0f
    const val SCALE_THRESHOLD = 0.2f
    const val MIN_SCALE = 0.3f

    val PEEK_HEIGHT = 56.dp
    const val PLAYER_ASPECT_RATIO = 16f / 9f
}


@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerBottomSheet(
    mainViewModel: MainViewModel,
    bottomSheetState: SheetState,
    bottomSheetOffset: () -> Float,
    onNavigateToChannelScreen: (String) -> Unit,
    onVideoDetailTouchActiveChanged: (Boolean) -> Unit,
) = trace("PlayerBottomSheet") {

    // ===== Collect all states here =====
    val mediaController by mainViewModel.mediaControllerFlow.collectAsStateWithLifecycle()
    val isPlaying by mainViewModel.isPlaying.collectAsStateWithLifecycle()
    val currentItem by mainViewModel.currentItem.collectAsStateWithLifecycle()
    val playerMode by mainViewModel.playerMode.collectAsStateWithLifecycle()

    if (currentItem == null && bottomSheetState.currentValue == SheetValue.Hidden) {
        return@trace
    }
    val videoDetailUiState by mainViewModel.videoDetailUiState.collectAsStateWithLifecycle()
    val pitchValue by mainViewModel.pitchValue.collectAsStateWithLifecycle()
    val tempoValue by mainViewModel.tempoValue.collectAsStateWithLifecycle()
    val isVocalRemovalEnabled by mainViewModel.isVocalRemovalEnabled.collectAsStateWithLifecycle()
    val isVocalRemovalSupported by mainViewModel.isVocalRemovalSupported.collectAsStateWithLifecycle()
    val myPlaylists by mainViewModel.myPlaylists.collectAsStateWithLifecycle()
    val currentPlaylist by mainViewModel.currentPlaylist.collectAsStateWithLifecycle()
    val currentPlaylistIndex by mainViewModel.currentPlaylistIndex.collectAsStateWithLifecycle()
    val currentPlaylistInfo by mainViewModel.currentPlaylistInfo.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showPlaylistModal by remember { mutableStateOf(false) }
    var showQualityModal by remember { mutableStateOf(false) }
    var showKaraokeMode by remember { mutableStateOf(false) }
    val playerHeight = LocalConfiguration.current.screenWidthDp.dp / GraphicsLayerConstants.PLAYER_ASPECT_RATIO
    val playerHeightPx = with(LocalDensity.current) { playerHeight.roundToPx() }
    val onVideoDetailTouchActiveChangedState by rememberUpdatedState(onVideoDetailTouchActiveChanged)

    val isSheetExpanded by remember(bottomSheetState) {
        derivedStateOf { bottomSheetState.currentValue == SheetValue.Expanded }
    }
    val isPlayerFullyExpanded by remember {
        derivedStateOf { isSheetExpanded && bottomSheetOffset() >= 0.999f }
    }
    var canShowPlayerOverlay by remember { mutableStateOf(false) }

    var isPlayerChromeVisible by remember { mutableStateOf(false) }
    val isVideoContentReady = currentItem is PlayableItem.Local ||
        videoDetailUiState is VideoDetailUiState.Success
    val canShowVideoControls = canShowPlayerOverlay && isVideoContentReady

    LaunchedEffect(isPlayerFullyExpanded) {
        if (isPlayerFullyExpanded) {
            delay(120)
            canShowPlayerOverlay = true
        } else {
            canShowPlayerOverlay = false
            isPlayerChromeVisible = false
        }
    }
    LaunchedEffect(canShowVideoControls) {
        if (!canShowVideoControls) {
            isPlayerChromeVisible = false
        }
    }

    if (showPlaylistModal) {
        trace("PlaylistModalBottomSheet") {
            PlaylistModalBottomSheetContainer(
                onDismiss = { showPlaylistModal = false },
                mainViewModel = mainViewModel,
                playerViewHeight = playerHeightPx
            )
        }
    }

    if (showQualityModal) {
        val videoQuality by mainViewModel.videoQuality.collectAsStateWithLifecycle()
        VideoQualityBottomSheet(
            videoDetailUiState = videoDetailUiState,
            currentQuality = videoQuality,
            onSetVideoQuality = mainViewModel::setVideoQuality,
            onDismiss = { showQualityModal = false }
        )
    }


    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        trace("MainPlayerLayout") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(playerHeight)
                    .graphicsLayer {
                        val progress = bottomSheetOffset()
                        scaleY = calculateScaleFactorY(progress, playerHeight)
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    }
                    .background(AppColors.BlueBackground)
                    .clickable {
                        coroutineScope.launch {
                            bottomSheetState.expand()
                        }
                    })
        }

        PlayerBottomSheetHeader(
            bottomSheetOffset = bottomSheetOffset,
            bottomSheetState = bottomSheetState,
            isPlaying = isPlaying,
            displayTitle = currentItem?.title ?: "",
            onPlayPause = mainViewModel::playPause,
            onStop = mainViewModel::stopPlayback
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(playerHeight)
                .graphicsLayer {
                    val progress = bottomSheetOffset()
                    scaleX = when {
                        progress < 0f -> GraphicsLayerConstants.MIN_SCALE
                        progress < GraphicsLayerConstants.SCALE_THRESHOLD -> calculateDefaultScaleX(progress)
                        else -> 1f
                    }
                    scaleY = calculateScaleFactorY(progress, playerHeight)
                    transformOrigin = TransformOrigin(0f, 0f)
                }
        ) {
            when (playerMode) {
                PlayerMode.VIDEO -> {
                    trace("PlayerView") {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    setBackgroundColor(android.graphics.Color.BLACK)
                                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                                    keepScreenOn = true
                                }
                            }, update = { view ->
                                val controller = mediaController
                                if (view.player != controller) {
                                    view.player = controller
                                }
                                if (view.useController) {
                                    view.useController = false
                                }
                            }, modifier = Modifier.fillMaxSize()
                        )
                    }
                    trace("PlayerThumbnailView") {
                        PlayerThumbnailView(
                            videoDetailUiState = videoDetailUiState,
                            currentItem = currentItem,
                            modifier = Modifier
                                .fillMaxSize()
                                .semantics { contentDescription = "PlayerThumbnailView" })
                    }
                    trace("PlayerLoadingIndicator") {
                        PlayerLoadingIndicator(
                            videoDetailUiState = videoDetailUiState,
                            currentItem = currentItem,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    if (canShowVideoControls) {
                        PlayerVideoChromeOverlay(
                            isPlaying = isPlaying,
                            isChromeVisible = isPlayerChromeVisible,
                            onChromeVisibleChange = { isPlayerChromeVisible = it },
                            mediaPositionProvider = {
                                mediaController?.currentPosition ?: 0L
                            },
                            mediaDurationProvider = {
                                mediaController?.duration?.takeIf { it > 0 } ?: 0L
                            },
                            onCollapse = {
                                coroutineScope.launch {
                                    bottomSheetState.partialExpand()
                                }
                            },
                            onQualityClick = { showQualityModal = true },
                            onFullscreenClick = { showKaraokeMode = true },
                            onPlayPause = mainViewModel::playPause,
                            onSeekBy = mainViewModel::seekBy,
                            onPrevious = mainViewModel::seekToPreviousItem,
                            onNext = mainViewModel::seekToNextItem,
                            hasPrevious = currentPlaylistIndex > 0,
                            hasNext = currentPlaylistIndex >= 0 &&
                                currentPlaylistIndex < currentPlaylist.lastIndex,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    PlayerVideoProgressTrack(
                        mediaPositionProvider = {
                            mediaController?.currentPosition ?: 0L
                        },
                        mediaDurationProvider = {
                            mediaController?.duration?.takeIf { it > 0 } ?: 0L
                        },
                        showThumb = canShowVideoControls && isPlayerChromeVisible,
                        enabled = canShowVideoControls,
                        onSeekTo = mainViewModel::seekTo,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(14.dp)
                            .graphicsLayer {
                                alpha = if (canShowVideoControls) 1f else 0f
                            }
                    )
                }

                PlayerMode.AUDIO -> {
                    AudioPlayerSurface(
                        currentItem = currentItem,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        VideoDetailPanel(
            videoDetailUiState = videoDetailUiState,
            currentItem = currentItem,
            myPlaylists = myPlaylists,
            pitchValue = pitchValue,
            tempoValue = tempoValue,
            onPlayVideo = mainViewModel::playVideo,
            onPitchPlusOne = mainViewModel::pitchPlusOne,
            onPitchMinusOne = mainViewModel::pitchMinusOne,
            onPitchInit = mainViewModel::initPitchValue,
            onTempoPlusOne = mainViewModel::tempoPlusOne,
            onTempoMinusOne = mainViewModel::tempoMinusOne,
            onTempoInit = mainViewModel::initTempoValue,
            onAddItemToPlaylist = mainViewModel::addItemToPlaylist,
            onNavigateToChannelScreen = onNavigateToChannelScreen,
            bottomSheetState = bottomSheetState,
            reservePlaylistButtonSpace = currentPlaylist.isNotEmpty(),
            modifier = Modifier
                .fillMaxSize()
                .offset {
                    val progress = bottomSheetOffset()
                    val visiblePlayerHeight = playerHeightPx * calculateScaleFactorY(
                        progress,
                        playerHeight
                    )
                    IntOffset(
                        x = 0,
                        y = (visiblePlayerHeight - playerHeightPx).roundToInt()
                    )
                }
                .padding(top = playerHeight)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial
                        )
                        onVideoDetailTouchActiveChangedState(true)
                        try {
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                            } while (event.changes.any { it.pressed })
                        } finally {
                            onVideoDetailTouchActiveChangedState(false)
                        }
                    }
                }
                .graphicsLayer {
                    val progress = bottomSheetOffset()
                    alpha = if (progress < 0) 1f else progress.pow(3).coerceAtLeast(0f)
                }
        )
        if (showKaraokeMode && currentItem != null) {
            KaraokeOverlay(
                item = currentItem!!,
                isPlaying = isPlaying,
                pitchUiValue = pitchValue,
                isVocalRemovalSupported = isVocalRemovalSupported,
                isVocalRemovalEnabled = isVocalRemovalEnabled,
                mediaPositionProvider = { mediaController?.currentPosition ?: 0L },
                onClose = { showKaraokeMode = false },
                onPlayPause = mainViewModel::playPause,
                onPitchMinusOne = mainViewModel::pitchMinusOne,
                onPitchPlusOne = mainViewModel::pitchPlusOne,
                onEnsureVocalRemovalEnabled = mainViewModel::ensureKaraokeVocalRemovalEnabled,
                modifier = Modifier.fillMaxSize(),
            )
        }

        PlaylistFloatingButton(
            currentPlaylist = currentPlaylist,
            playlistTitle = currentPlaylistInfo?.title,
            bottomSheetState = bottomSheetState,
            onClick = { showPlaylistModal = true },
            bottomSheetOffset = bottomSheetOffset,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
        )

    }
}

@Composable
private fun PlayerVideoChromeOverlay(
    isPlaying: Boolean,
    isChromeVisible: Boolean,
    onChromeVisibleChange: (Boolean) -> Unit,
    mediaPositionProvider: () -> Long,
    mediaDurationProvider: () -> Long,
    onCollapse: () -> Unit,
    onQualityClick: () -> Unit,
    onFullscreenClick: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    hasPrevious: Boolean,
    hasNext: Boolean,
    modifier: Modifier = Modifier
) {
    var displayedPositionMs by remember { mutableLongStateOf(mediaPositionProvider().coerceAtLeast(0L)) }
    var displayedDurationMs by remember { mutableLongStateOf(mediaDurationProvider().coerceAtLeast(0L)) }
    var seekFeedbackDirection by remember { mutableIntStateOf(0) }
    var suppressSingleTapUntilMs by remember { mutableLongStateOf(0L) }
    val isChromeVisibleState by rememberUpdatedState(isChromeVisible)
    val mediaPositionProviderState by rememberUpdatedState(mediaPositionProvider)
    val mediaDurationProviderState by rememberUpdatedState(mediaDurationProvider)

    LaunchedEffect(Unit) {
        while (true) {
            displayedPositionMs = mediaPositionProviderState().coerceAtLeast(0L)
            displayedDurationMs = mediaDurationProviderState().coerceAtLeast(0L)
            delay(300)
        }
    }

    LaunchedEffect(seekFeedbackDirection) {
        if (seekFeedbackDirection != 0) {
            delay(650)
            seekFeedbackDirection = 0
        }
    }

    LaunchedEffect(isChromeVisible) {
        if (isChromeVisible) {
            delay(3_000)
            onChromeVisibleChange(false)
        }
    }

    fun handleSingleTap() {
        if (SystemClock.uptimeMillis() < suppressSingleTapUntilMs) {
            return
        }
        seekFeedbackDirection = 0
        onChromeVisibleChange(!isChromeVisibleState)
    }
    fun handleDoubleTap(direction: Int) {
        suppressSingleTapUntilMs = SystemClock.uptimeMillis() + PLAYER_DOUBLE_TAP_SINGLE_TAP_SUPPRESS_MS
        onChromeVisibleChange(false)
        seekFeedbackDirection = direction
        onSeekBy(direction * PLAYER_DOUBLE_TAP_SEEK_MS)
    }

    Box(
        modifier = modifier
    ) {
        if (isChromeVisible) {
            PlayerChromeScrim(modifier = Modifier.fillMaxSize())
        }
        PlayerGestureLayer(
            isChromeVisible = { isChromeVisibleState },
            onTap = ::handleSingleTap,
            onSeekBackward = { handleDoubleTap(-1) },
            onSeekForward = { handleDoubleTap(1) },
            modifier = Modifier.fillMaxSize()
        )
        if (isChromeVisible) {
            PlayerChromeControls(
                isPlaying = isPlaying,
                displayedPositionMs = displayedPositionMs,
                displayedDurationMs = displayedDurationMs,
                onCollapse = onCollapse,
                onQualityClick = onQualityClick,
                onFullscreenClick = onFullscreenClick,
                onPlayPause = onPlayPause,
                onPrevious = onPrevious,
                onNext = onNext,
                hasPrevious = hasPrevious,
                hasNext = hasNext,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (seekFeedbackDirection != 0) {
            SeekFeedbackBadge(
                direction = seekFeedbackDirection,
                modifier = Modifier
                    .align(
                        if (seekFeedbackDirection < 0) {
                            Alignment.CenterStart
                        } else {
                            Alignment.CenterEnd
                        }
                    )
                    .padding(horizontal = 58.dp)
            )
        }
    }
}

private const val PLAYER_DOUBLE_TAP_SEEK_MS = 5_000L
private const val PLAYER_DOUBLE_TAP_TIMEOUT_MS = 110L
private const val PLAYER_DOUBLE_TAP_SINGLE_TAP_SUPPRESS_MS = 720L

@Composable
private fun PlayerChromeScrim(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.background(
            Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color.Black.copy(alpha = 0.45f),
                    0.46f to Color.Black.copy(alpha = 0.18f),
                    1f to Color.Black.copy(alpha = 0.52f)
                )
            )
        )
    )
}

@Composable
private fun PlayerGestureLayer(
    isChromeVisible: () -> Boolean,
    onTap: () -> Unit,
    onSeekBackward: () -> Unit,
    onSeekForward: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isChromeVisibleState by rememberUpdatedState(isChromeVisible)
    val onTapState by rememberUpdatedState(onTap)
    val onSeekBackwardState by rememberUpdatedState(onSeekBackward)
    val onSeekForwardState by rememberUpdatedState(onSeekForward)

    Row(modifier = modifier) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(Unit) {
                    detectPlayerTapGestures(
                        isChromeVisible = { isChromeVisibleState() },
                        onTap = { onTapState() },
                        onDoubleTap = { onSeekBackwardState() }
                    )
                }
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(Unit) {
                    detectPlayerTapGestures(
                        isChromeVisible = { isChromeVisibleState() },
                        onTap = { onTapState() },
                        onDoubleTap = { onSeekForwardState() }
                    )
                }
        )
    }
}

@Composable
private fun PlayerChromeControls(
    isPlaying: Boolean,
    displayedPositionMs: Long,
    displayedDurationMs: Long,
    onCollapse: () -> Unit,
    onQualityClick: () -> Unit,
    onFullscreenClick: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    hasPrevious: Boolean,
    hasNext: Boolean,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp)
                .align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChromeIconButton(
                iconRes = R.drawable.baseline_arrow_back_24,
                contentDescription = "Collapse player",
                onClick = onCollapse,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.weight(1f))
            ChromeIconButton(
                iconRes = R.drawable.baseline_settings_24,
                contentDescription = "Quality settings",
                onClick = onQualityClick,
                modifier = Modifier.size(30.dp)
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 98.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChromeIconButton(
                iconRes = R.drawable.baseline_skip_previous_24,
                contentDescription = "Previous item",
                onClick = onPrevious,
                enabled = hasPrevious,
                modifier = Modifier.size(42.dp)
            )
            ChromeIconButton(
                iconRes = if (isPlaying) {
                    R.drawable.baseline_pause_24
                } else {
                    R.drawable.baseline_play_arrow_24
                },
                contentDescription = if (isPlaying) "Pause" else "Play",
                onClick = onPlayPause,
                modifier = Modifier.size(48.dp)
            )
            ChromeIconButton(
                iconRes = R.drawable.baseline_skip_next_24,
                contentDescription = "Next item",
                onClick = onNext,
                enabled = hasNext,
                modifier = Modifier.size(42.dp)
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${formatPlaybackTime(displayedPositionMs)} / ${formatPlaybackTime(displayedDurationMs)}",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            Spacer(modifier = Modifier.weight(1f))
            ChromeIconButton(
                iconRes = R.drawable.baseline_mic_24,
                contentDescription = "Karaoke",
                onClick = onFullscreenClick,
                modifier = Modifier.size(30.dp)
            )
        }
    }
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectPlayerTapGestures(
    isChromeVisible: () -> Boolean,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit
) {
    awaitEachGesture {
        val firstDown = awaitFirstDown(requireUnconsumed = false)
        val wasChromeVisible = isChromeVisible()
        val firstPosition = firstDown.position
        var moved = false
        do {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull()
            if (change != null && (change.position - firstPosition).getDistance() > viewConfiguration.touchSlop) {
                moved = true
            }
        } while (event.changes.any { it.pressed })

        if (moved) {
            return@awaitEachGesture
        }

        if (wasChromeVisible) {
            onTap()
        }

        val secondDown = withTimeoutOrNull(PLAYER_DOUBLE_TAP_TIMEOUT_MS) {
            awaitFirstDown(requireUnconsumed = false)
        }
        if (secondDown != null) {
            val secondPosition = secondDown.position
            var secondMoved = false
            do {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull()
                if (
                    change != null &&
                    (change.position - secondPosition).getDistance() > viewConfiguration.touchSlop
                ) {
                    secondMoved = true
                }
            } while (event.changes.any { it.pressed })
            if (!secondMoved) {
                onDoubleTap()
            }
        } else if (!wasChromeVisible) {
            onTap()
        }
    }
}

private fun formatPlaybackTime(positionMs: Long): String {
    val totalSeconds = (positionMs.coerceAtLeast(0L) / 1000L).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

@Composable
private fun ChromeIconButton(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = Color.White.copy(alpha = if (enabled) 1f else 0.34f),
            modifier = Modifier.fillMaxSize(0.9f)
        )
    }
}

@Composable
private fun PlayerVideoProgressTrack(
    mediaPositionProvider: () -> Long,
    mediaDurationProvider: () -> Long,
    showThumb: Boolean,
    enabled: Boolean,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var displayedPositionMs by remember { mutableLongStateOf(mediaPositionProvider().coerceAtLeast(0L)) }
    var displayedDurationMs by remember { mutableLongStateOf(mediaDurationProvider().coerceAtLeast(0L)) }
    var dragPositionMs by remember { mutableLongStateOf(0L) }
    var isDragging by remember { mutableStateOf(false) }
    val mediaPositionProviderState by rememberUpdatedState(mediaPositionProvider)
    val mediaDurationProviderState by rememberUpdatedState(mediaDurationProvider)

    LaunchedEffect(Unit) {
        while (true) {
            if (!isDragging) {
                displayedPositionMs = mediaPositionProviderState().coerceAtLeast(0L)
                displayedDurationMs = mediaDurationProviderState().coerceAtLeast(0L)
            }
            delay(300)
        }
    }

    PlaybackScrubber(
        positionMs = if (isDragging) dragPositionMs else displayedPositionMs,
        durationMs = displayedDurationMs.takeIf { it > 0 } ?: 1L,
        showThumb = showThumb || isDragging,
        enabled = enabled,
        onPreviewPosition = { positionMs ->
            isDragging = true
            dragPositionMs = positionMs
        },
        onSeekTo = { positionMs ->
            onSeekTo(positionMs)
            dragPositionMs = positionMs
            displayedPositionMs = positionMs
            isDragging = false
        },
        onScrubCancel = {
            isDragging = false
        },
        modifier = modifier
    )
}

@Composable
private fun SeekFeedbackBadge(
    direction: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.size(74.dp),
        shape = RoundedCornerShape(37.dp),
        color = Color.Black.copy(alpha = 0.38f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(
                    if (direction < 0) {
                        R.string.player_seek_backward_feedback
                    } else {
                        R.string.player_seek_forward_feedback
                    }
                ),
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun PlaybackScrubber(
    positionMs: Long,
    durationMs: Long,
    showThumb: Boolean,
    enabled: Boolean,
    onPreviewPosition: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onScrubCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var trackWidthPx by remember { mutableIntStateOf(0) }
    var scrubberHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val trackHeightPx = with(density) { 2.dp.roundToPx() }
    val thumbDiameterPx = with(density) { 6.dp.roundToPx() }
    val thumbRadiusPx = thumbDiameterPx / 2
    val safeDuration = durationMs.coerceAtLeast(1L)
    val progress = (positionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    val trackTopPx = (scrubberHeightPx - trackHeightPx).coerceAtLeast(0)
    val trackCenterYPx = trackTopPx + (trackHeightPx / 2f)
    val thumbTopPx = (trackCenterYPx - (thumbDiameterPx / 2f)).roundToInt()

    fun positionFromOffset(offset: Offset): Long {
        val width = trackWidthPx.coerceAtLeast(1)
        return ((offset.x.coerceIn(0f, width.toFloat()) / width.toFloat()) * safeDuration).toLong()
    }

    Box(
        modifier = modifier
            .onSizeChanged {
                trackWidthPx = it.width
                scrubberHeightPx = it.height
            }
            .then(
                if (enabled) {
                    Modifier.pointerInput(safeDuration) {
                        awaitEachGesture {
                            var latestPosition = 0L
                            var activeScrub = false
                            var commitScrub = false
                            try {
                                val down = awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = PointerEventPass.Initial
                                )
                                down.consume()
                                activeScrub = true
                                latestPosition = positionFromOffset(down.position)
                                onPreviewPosition(latestPosition)
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull()
                                    event.changes.forEach { it.consume() }
                                    if (change != null) {
                                        latestPosition = positionFromOffset(change.position)
                                        onPreviewPosition(latestPosition)
                                    }
                                } while (event.changes.any { it.pressed })
                                commitScrub = true
                            } finally {
                                if (activeScrub) {
                                    if (commitScrub) {
                                        onSeekTo(latestPosition)
                                    } else {
                                        onScrubCancel()
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Modifier
                }
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, trackTopPx) }
                .height(2.dp)
                .background(Color.White.copy(alpha = 0.42f))
            )
        Box(
            modifier = Modifier
                .fillMaxWidth(progress)
                .offset { IntOffset(0, trackTopPx) }
                .height(2.dp)
                .background(AppColors.BlueBackground)
        )
        if (showThumb) {
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = (trackWidthPx * progress).roundToInt() - thumbRadiusPx,
                            y = thumbTopPx
                        )
                    }
                    .size(6.dp)
                    .background(AppColors.BlueBackground, RoundedCornerShape(3.dp))
            )
        }
    }
}

@Composable
private fun AudioPlayerSurface(
    currentItem: PlayableItem?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(AppColors.BlueBackground)
            .padding(horizontal = 28.dp)
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = Color.White.copy(alpha = 0.14f),
                modifier = Modifier
                    .fillMaxWidth(0.48f)
                    .height(150.dp)
            ) {}
            Text(
                text = currentItem?.title.orEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            val subtitle = (currentItem as? PlayableItem.Local)?.artist
                ?: (currentItem as? PlayableItem.Remote)?.video?.uploaderName
                ?: ""
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    modifier = Modifier.padding(top = 6.dp),
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.76f),
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

private fun Modifier.changeMainBackgroundAlpha(bottomSheetOffset: Float): Modifier {
    if (bottomSheetOffset < 0) return this.graphicsLayer { alpha = 1f }
    return this.graphicsLayer { alpha = (bottomSheetOffset.pow(3)).coerceAtLeast(0f) }
}

private fun calculateDefaultScaleX(bottomSheetOffset: Float): Float {
    val t = (bottomSheetOffset / 0.2f).coerceIn(0f, 1f)
    val easedT = t * t * t
    return GraphicsLayerConstants.MIN_SCALE + (1f - GraphicsLayerConstants.MIN_SCALE) * easedT
}

private fun calculateScaleFactorY(bottomSheetOffset: Float, playerHeight: androidx.compose.ui.unit.Dp): Float {
    val minScale = PEEK_HEIGHT.value / playerHeight.value
    return when {
        bottomSheetOffset <= GraphicsLayerConstants.FULLY_EXPANDED -> minScale
        else -> lerp(start = minScale, stop = 1f, fraction = bottomSheetOffset)
    }
}
