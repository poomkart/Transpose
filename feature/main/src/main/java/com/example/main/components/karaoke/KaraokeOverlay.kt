package com.example.main.components.karaoke

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.playable.PlayableItem
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
internal fun KaraokeOverlay(
    item: PlayableItem,
    isPlaying: Boolean,
    pitchUiValue: Int,
    isVocalRemovalSupported: Boolean,
    isVocalRemovalEnabled: Boolean,
    mediaPositionProvider: () -> Long,
    onClose: () -> Unit,
    onPlayPause: () -> Unit,
    onPitchMinusOne: () -> Unit,
    onPitchPlusOne: () -> Unit,
    onEnsureVocalRemovalEnabled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var lyrics by remember(item.id) {
        mutableStateOf<KaraokeLyricsResult>(KaraokeLyricsResult.Loading)
    }
    var positionMs by remember {
        mutableLongStateOf(mediaPositionProvider().coerceAtLeast(0L))
    }
    var manualOffsetMs by remember(item.id) { mutableLongStateOf(0L) }
    var autoSyncEnabled by remember(item.id) { mutableStateOf(true) }
    val positionProvider by rememberUpdatedState(mediaPositionProvider)

    val context = LocalContext.current
    val audioEffectsManager = remember(context) {
        karaokeAudioEffectsManager(context)
    }
    val liveVocalRemovalEnabled by audioEffectsManager.isVocalRemovalEnabled.collectAsStateWithLifecycle()
    val vocalRemovalMix by audioEffectsManager.vocalRemovalMix.collectAsStateWithLifecycle()
    val vocalOnlyMode by audioEffectsManager.isVocalOnlyMode.collectAsStateWithLifecycle()

    fun setRemovalEnabled(enabled: Boolean) {
        if (audioEffectsManager.isVocalRemovalEnabled.value != enabled) {
            audioEffectsManager.updateIsVocalRemovalEnabled()
        }
    }

    fun setVocalOnly(enabled: Boolean) {
        if (audioEffectsManager.isVocalOnlyMode.value != enabled) {
            audioEffectsManager.updateIsVocalOnlyMode()
        }
    }

    fun setNormalVocalPercent(percent: Float) {
        val safePercent = percent.coerceIn(0f, 100f)
        setVocalOnly(false)

        if (safePercent >= 99.5f) {
            audioEffectsManager.updateVocalRemovalMix(0f)
            setRemovalEnabled(false)
        } else {
            setRemovalEnabled(true)
            audioEffectsManager.updateVocalRemovalMix(1f - (safePercent / 100f))
        }
    }

    fun setVocalOnlyPreset() {
        setRemovalEnabled(true)
        setVocalOnly(true)
        audioEffectsManager.updateVocalRemovalMix(1f)
    }

    val vocalPercent = when {
        vocalOnlyMode -> 100
        !liveVocalRemovalEnabled -> 100
        else -> ((1f - vocalRemovalMix.coerceIn(0f, 1f)) * 100f).roundToInt()
    }

    val vocalStatus = when {
        !isVocalRemovalSupported -> "Vocal removal unavailable"
        vocalOnlyMode -> "Vocal Only"
        !liveVocalRemovalEnabled -> "Original"
        vocalPercent <= 5 -> "Instrumental"
        else -> "Guide vocal $vocalPercent%"
    }

    val syncedResult = lyrics as? KaraokeLyricsResult.Synced
    val sourceLabel = when (val current = lyrics) {
        KaraokeLyricsResult.Loading -> "กำลังหาเนื้อเพลง..."
        is KaraokeLyricsResult.Synced -> current.sourceLabel
        is KaraokeLyricsResult.Plain -> current.sourceLabel
        is KaraokeLyricsResult.Error -> "ไม่พบแหล่งเนื้อเพลง"
    }
    val automaticOffsetMs = syncedResult?.autoOffsetMs ?: 0L
    val appliedAutomaticOffsetMs = if (autoSyncEnabled) automaticOffsetMs else 0L
    val syncedPositionMs = (
        positionMs - appliedAutomaticOffsetMs + manualOffsetMs
        ).coerceAtLeast(0L)

    LaunchedEffect(item.id) {
        manualOffsetMs = 0L
        autoSyncEnabled = true

        if (isVocalRemovalSupported) {
            if (!isVocalRemovalEnabled) {
                onEnsureVocalRemovalEnabled()
            }
            setVocalOnly(false)
            audioEffectsManager.updateVocalRemovalMix(1f)
        }

        lyrics = if (item is PlayableItem.Remote) {
            val detail = try {
                karaokeVideoRepository(context)
                    .fetchVideoDetail(item.video)
                    .getOrNull()
            } catch (_: Exception) {
                null
            }

            val youtubeSynced = detail?.subtitleTracks?.let { tracks ->
                KaraokeLyricsRepository.fromYouTubeSubtitles(
                    tracks = tracks,
                    videoTitle = item.title,
                )
            }

            if (youtubeSynced != null) {
                youtubeSynced
            } else {
                val primaryLyrics = KaraokeLyricsRepository.get(item)
                if (primaryLyrics is KaraokeLyricsResult.Error && detail != null) {
                    KaraokeLyricsRepository.fromDescription(detail.description) ?: primaryLyrics
                } else {
                    primaryLyrics
                }
            }
        } else {
            KaraokeLyricsRepository.get(item)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            positionMs = positionProvider().coerceAtLeast(0L)
            delay(100)
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = Color(0xFF080B12),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) {
                        Icon(
                            painter = painterResource(android.R.drawable.ic_menu_close_clear_cancel),
                            contentDescription = "Close karaoke",
                            tint = Color.White,
                        )
                    }
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            "KARAOKE",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            item.title,
                            color = Color.White.copy(alpha = .72f),
                            maxLines = 1,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            vocalStatus,
                            color = if (liveVocalRemovalEnabled) {
                                Color(0xFF75E6A4)
                            } else {
                                Color.White.copy(alpha = .58f)
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            "Lyrics: $sourceLabel",
                            color = Color(0xFF8EC5FF),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 96.dp, bottom = 332.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when (val result = lyrics) {
                        KaraokeLyricsResult.Loading -> CircularProgressIndicator()
                        is KaraokeLyricsResult.Error -> Text(
                            result.message,
                            color = Color.White.copy(alpha = .7f),
                            textAlign = TextAlign.Center,
                        )
                        is KaraokeLyricsResult.Plain -> PlainLyrics(result)
                        is KaraokeLyricsResult.Synced -> SyncedLyrics(
                            lines = result.lines,
                            positionMs = syncedPositionMs,
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                ) {
                    KaraokeControls(
                        isPlaying = isPlaying,
                        pitchUiValue = pitchUiValue,
                        manualOffsetMs = manualOffsetMs,
                        autoOffsetMs = automaticOffsetMs,
                        autoSyncEnabled = autoSyncEnabled,
                        syncSource = syncedResult?.sourceLabel,
                        hasSyncedLyrics = syncedResult != null,
                        vocalRemovalSupported = isVocalRemovalSupported,
                        vocalOnlyMode = vocalOnlyMode,
                        vocalPercent = vocalPercent,
                        onPlayPause = onPlayPause,
                        onPitchMinusOne = onPitchMinusOne,
                        onPitchPlusOne = onPitchPlusOne,
                        onVocalPercentChange = ::setNormalVocalPercent,
                        onOriginal = { setNormalVocalPercent(100f) },
                        onGuide = { setNormalVocalPercent(30f) },
                        onInstrumental = { setNormalVocalPercent(0f) },
                        onVocalOnly = ::setVocalOnlyPreset,
                        onToggleAutoSync = { autoSyncEnabled = !autoSyncEnabled },
                        onOffsetMinus = {
                            manualOffsetMs = (manualOffsetMs - 500L)
                                .coerceAtLeast(-30_000L)
                        },
                        onOffsetPlus = {
                            manualOffsetMs = (manualOffsetMs + 500L)
                                .coerceAtMost(30_000L)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlainLyrics(result: KaraokeLyricsResult.Plain) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "เนื้อเพลงจาก ${result.sourceLabel} ไม่มี timestamp สำหรับ sync",
            color = Color(0xFFFFC66D),
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
        ) {
            item {
                Text(
                    result.text,
                    color = Color.White,
                    fontSize = 20.sp,
                    lineHeight = 32.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun SyncedLyrics(
    lines: List<KaraokeLyricLine>,
    positionMs: Long,
) {
    val listState = rememberLazyListState()
    val activeIndex = remember(lines, positionMs) {
        lines.indexOfLast { it.timeMs <= positionMs }.coerceAtLeast(0)
    }

    LaunchedEffect(activeIndex) {
        if (lines.isNotEmpty()) {
            listState.animateScrollToItem((activeIndex - 2).coerceAtLeast(0))
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
    ) {
        itemsIndexed(lines) { index, line ->
            val active = index == activeIndex
            Text(
                text = line.text,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = if (active) 12.dp else 8.dp),
                color = if (active) Color.White else Color.White.copy(alpha = .42f),
                fontSize = if (active) 28.sp else 20.sp,
                lineHeight = if (active) 36.sp else 28.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun KaraokeControls(
    isPlaying: Boolean,
    pitchUiValue: Int,
    manualOffsetMs: Long,
    autoOffsetMs: Long,
    autoSyncEnabled: Boolean,
    syncSource: String?,
    hasSyncedLyrics: Boolean,
    vocalRemovalSupported: Boolean,
    vocalOnlyMode: Boolean,
    vocalPercent: Int,
    onPlayPause: () -> Unit,
    onPitchMinusOne: () -> Unit,
    onPitchPlusOne: () -> Unit,
    onVocalPercentChange: (Float) -> Unit,
    onOriginal: () -> Unit,
    onGuide: () -> Unit,
    onInstrumental: () -> Unit,
    onVocalOnly: () -> Unit,
    onToggleAutoSync: () -> Unit,
    onOffsetMinus: () -> Unit,
    onOffsetPlus: () -> Unit,
) {
    val semitones = (pitchUiValue - 100) / 10f
    val youtubeTimed = syncSource?.startsWith("YouTube captions") == true

    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = .06f))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onPitchMinusOne) { Text("Key −") }
            Text(
                text = if (semitones == 0f) "Original" else "%+.0f".format(semitones),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
            Button(onClick = onPitchPlusOne) { Text("Key +") }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = if (vocalOnlyMode) "🎤 Vocal Only" else "🎤 เสียงร้อง $vocalPercent%",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium,
        )

        Slider(
            value = vocalPercent.toFloat(),
            onValueChange = onVocalPercentChange,
            valueRange = 0f..100f,
            enabled = vocalRemovalSupported && !vocalOnlyMode,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onOriginal, enabled = vocalRemovalSupported) {
                Text("Original")
            }
            Button(onClick = onGuide, enabled = vocalRemovalSupported) {
                Text("Guide 30%")
            }
            Button(onClick = onInstrumental, enabled = vocalRemovalSupported) {
                Text("Instrumental")
            }
            Button(onClick = onVocalOnly, enabled = vocalRemovalSupported) {
                Text("Vocal Only")
            }
        }

        Spacer(Modifier.height(8.dp))

        if (hasSyncedLyrics) {
            val syncStatus = when {
                youtubeTimed -> "🎬 MV Sync: YouTube timeline"
                autoOffsetMs > 0L && autoSyncEnabled -> {
                    "🎬 MV Auto: +${formatSeconds(autoOffsetMs)}s"
                }
                autoOffsetMs > 0L -> "🎬 MV Auto: ปิด"
                else -> "🎵 Sync: ${syncSource ?: "lyrics timeline"}"
            }
            Text(
                syncStatus,
                color = Color(0xFF8EC5FF),
                style = MaterialTheme.typography.labelMedium,
            )

            if (!youtubeTimed && autoOffsetMs > 0L) {
                Button(onClick = onToggleAutoSync) {
                    Text(if (autoSyncEnabled) "MV Auto ON" else "MV Auto OFF")
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onOffsetMinus) { Text("Lyrics −0.5s") }
                Button(onClick = onPlayPause) {
                    Text(if (isPlaying) "Pause" else "Play")
                }
                Button(onClick = onOffsetPlus) { Text("Lyrics +0.5s") }
            }

            Text(
                "Manual ${formatSignedSeconds(manualOffsetMs)}s",
                color = Color.White.copy(alpha = .55f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            Button(onClick = onPlayPause) {
                Text(if (isPlaying) "Pause" else "Play")
            }
            Text(
                "ยังไม่มี timeline สำหรับไฮไลต์อัตโนมัติ",
                color = Color.White.copy(alpha = .55f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun formatSeconds(ms: Long): String =
    "%.1f".format(ms / 1000.0)

private fun formatSignedSeconds(ms: Long): String =
    "%+.1f".format(ms / 1000.0)
