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
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.domain.model.playable.PlayableItem
import kotlinx.coroutines.delay

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

    LaunchedEffect(item.id) {
        onEnsureVocalRemovalEnabled()
        lyrics = KaraokeLyricsRepository.get(item)
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
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) {
                        Icon(
                            painter = painterResource(android.R.drawable.ic_menu_close_clear_cancel),
                            contentDescription = "Close karaoke",
                            tint = Color.White,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text("KARAOKE", color = Color.White, fontWeight = FontWeight.Bold)
                        Text(
                            item.title,
                            color = Color.White.copy(alpha = .72f),
                            maxLines = 1,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        if (!isVocalRemovalSupported) "Vocal: N/A"
                        else if (isVocalRemovalEnabled) "Vocal: OFF" else "Vocal: ON",
                        color = if (isVocalRemovalEnabled) Color(0xFF75E6A4) else Color.White.copy(alpha = .58f),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }

                Spacer(Modifier.height(8.dp))

                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
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

                KaraokeControls(
                    isPlaying = isPlaying,
                    pitchUiValue = pitchUiValue,
                    offsetMs = offsetMs,
                    onPlayPause = onPlayPause,
                    onPitchMinusOne = onPitchMinusOne,
                    onPitchPlusOne = onPitchPlusOne,
                    onOffsetMinus = { offsetMs = (offsetMs - 500L).coerceAtLeast(-10_000L) },
                    onOffsetPlus = { offsetMs = (offsetMs + 500L).coerceAtMost(10_000L) },
                )
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
    onPlayPause: () -> Unit,
    onPitchMinusOne: () -> Unit,
    onPitchPlusOne: () -> Unit,
    onOffsetMinus: () -> Unit,
    onOffsetPlus: () -> Unit,
) {
    val semitones = (pitchUiValue - 100) / 10f
    Column(
        Modifier.fillMaxWidth().background(Color.White.copy(alpha = .06f)).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
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
