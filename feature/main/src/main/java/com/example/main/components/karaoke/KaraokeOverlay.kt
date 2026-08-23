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
    var lyrics by remember(item.id) { mutableStateOf<KaraokeLyricsResult>(KaraokeLyricsResult.Loading) }
    var positionMs by remember { mutableLongStateOf(mediaPositionProvider().coerceAtLeast(0L)) }
    var offsetMs by remember(item.id) { mutableLongStateOf(0L) }
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

    LaunchedEffect(item.id) {
        if (isVocalRemovalSupported) {
            if (!isVocalRemovalEnabled) {
                onEnsureVocalRemovalEnabled()
            }
            setVocalOnly(false)
            audioEffectsManager.updateVocalRemovalMix(1f)
        }

        val primaryLyrics = KaraokeLyricsRepository.get(item)
        lyrics = if (primaryLyrics is KaraokeLyricsResult.Error && item is PlayableItem.Remote) {
            val fullDescription = try {
                karaokeVideoRepository(context)
                    .fetchVideoDetail(item.video)
                    .getOrNull()
                    ?.description
            } catch (_: Exception) {
                null
            }

            KaraokeLyricsRepository.fromDescription(fullDescription.orEmpty()) ?: primaryLyrics
        } else {
            primaryLyrics
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
        Surface(modifier = modifier.fillMaxSize(), color = Color(0xFF080B12)) {
            Box(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
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
                        Text("KARAOKE", color = Color.White, fontWeight = FontWeight.Bold)
                        Text(
                            item.title,
                            color = Color.White.copy(alpha = .72f),
                            maxLines = 1,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            vocalStatus,
                            color = if (liveVocalRemovalEnabled) Color(0xFF75E6A4) else Color.White.copy(alpha = .58f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 76.dp, bottom = 286.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when (val result = lyrics) {
                        KaraokeLyricsResult.Loading -> CircularProgressIndicator()
                        is KaraokeLyricsResult.Error -> Text(
                            result.message,
                            color = Color.White.copy(alpha = .7f),
                            textAlign = TextAlign.Center,
                        )
                        is KaraokeLyricsResult.Plain -> LazyColumn(
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
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                )
                            }
                        }
                        is KaraokeLyricsResult.Synced -> SyncedLyrics(
                            lines = result.lines,
                            positionMs = positionMs + offsetMs,
                        )
                    }
                }

                Box(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                ) {
                    KaraokeControls(
                        isPlaying = isPlaying,
                        pitchUiValue = pitchUiValue,
                        offsetMs = offsetMs,
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
                        onOffsetMinus = { offsetMs = (offsetMs - 500L).coerceAtLeast(-10_000L) },
                        onOffsetPlus = { offsetMs = (offsetMs + 500L).coerceAtMost(10_000L) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncedLyrics(lines: List<KaraokeLyricLine>, positionMs: Long) {
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
                modifier = Modifier.fillMaxWidth().padding(vertical = if (active) 12.dp else 8.dp),
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
    offsetMs: Long,
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
    onOffsetMinus: () -> Unit,
    onOffsetPlus: () -> Unit,
) {
    val semitones = (pitchUiValue - 100) / 10f

    Column(
        Modifier.fillMaxWidth().background(Color.White.copy(alpha = .06f)).padding(12.dp),
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

        Spacer(Modifier.height(8.dp))
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
            Button(onClick = onOriginal, enabled = vocalRemovalSupported) { Text("Original") }
            Button(onClick = onGuide, enabled = vocalRemovalSupported) { Text("Guide 30%") }
        }

        Spacer(Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onInstrumental, enabled = vocalRemovalSupported) { Text("Instrumental") }
            Button(onClick = onVocalOnly, enabled = vocalRemovalSupported) { Text("Vocal Only") }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onOffsetMinus) { Text("Lyrics −0.5s") }
            Button(onClick = onPlayPause) { Text(if (isPlaying) "Pause" else "Play") }
            Button(onClick = onOffsetPlus) { Text("Lyrics +0.5s") }
        }

        Text(
            "Sync ${if (offsetMs >= 0) "+" else ""}${offsetMs / 1000.0}s",
            color = Color.White.copy(alpha = .55f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
