#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else '.').resolve()
vm = root / 'feature/main/src/main/java/com/example/main/MainViewModel.kt'
player = root / 'feature/main/src/main/java/com/example/main/components/bottomsheet/PlayerBottomSheet.kt'
karaoke_lyrics = root / 'feature/main/src/main/java/com/example/main/components/karaoke/KaraokeLyrics.kt'

for f in (vm, player, karaoke_lyrics):
    if not f.exists():
        raise SystemExit(f'Missing expected file: {f}')

# MainViewModel wrappers for karaoke vocal-removal state/actions.
s = vm.read_text()
needle = '    val pitchValue = audioEffectsManager.pitchValue\n    val tempoValue = audioEffectsManager.tempoValue\n'
addition = '''    val pitchValue = audioEffectsManager.pitchValue
    val tempoValue = audioEffectsManager.tempoValue

    val isVocalRemovalEnabled = audioEffectsManager.isVocalRemovalEnabled
    val isVocalRemovalSupported = audioEffectsManager.isVocalRemovalSupported

    fun ensureKaraokeVocalRemovalEnabled() {
        if (isVocalRemovalSupported.value && !isVocalRemovalEnabled.value) {
            audioEffectsManager.updateIsVocalRemovalEnabled()
        }
    }
'''
if 'ensureKaraokeVocalRemovalEnabled' not in s:
    if needle not in s:
        raise SystemExit('MainViewModel anchor not found')
    s = s.replace(needle, addition, 1)
    vm.write_text(s)

# PlayerBottomSheet integration.
s = player.read_text()
if 'components.karaoke.KaraokeOverlay' not in s:
    anchor = 'import com.example.main.components.bottomsheet.state.VideoDetailUiState\n'
    if anchor not in s:
        raise SystemExit('Player import anchor not found')
    s = s.replace(anchor, anchor + 'import com.example.main.components.karaoke.KaraokeOverlay\n', 1)

state_anchor = '    val pitchValue by mainViewModel.pitchValue.collectAsStateWithLifecycle()\n    val tempoValue by mainViewModel.tempoValue.collectAsStateWithLifecycle()\n'
if 'val isVocalRemovalEnabled by mainViewModel.isVocalRemovalEnabled' not in s:
    if state_anchor not in s:
        raise SystemExit('Player state anchor not found')
    s = s.replace(
        state_anchor,
        state_anchor +
        '    val isVocalRemovalEnabled by mainViewModel.isVocalRemovalEnabled.collectAsStateWithLifecycle()\n'
        '    val isVocalRemovalSupported by mainViewModel.isVocalRemovalSupported.collectAsStateWithLifecycle()\n',
        1,
    )

modal_anchor = '    var showPlaylistModal by remember { mutableStateOf(false) }\n    var showQualityModal by remember { mutableStateOf(false) }\n'
if 'showKaraokeMode' not in s:
    if modal_anchor not in s:
        raise SystemExit('Player modal anchor not found')
    s = s.replace(modal_anchor, modal_anchor + '    var showKaraokeMode by remember { mutableStateOf(false) }\n', 1)

old_callback = '''                            onFullscreenClick = {
                                ToastUtil.showShort(context, R.string.fullscreen_mode_coming_soon)
                            },'''
new_callback = '                            onFullscreenClick = { showKaraokeMode = true },'
if old_callback in s:
    s = s.replace(old_callback, new_callback, 1)
elif new_callback not in s:
    raise SystemExit('Fullscreen callback anchor not found')

s = s.replace('iconRes = R.drawable.baseline_fullscreen_24,', 'iconRes = R.drawable.baseline_mic_24,', 1)
s = s.replace('contentDescription = "Fullscreen",', 'contentDescription = "Karaoke",', 1)

overlay_anchor = '''        PlaylistFloatingButton(
            currentPlaylist = currentPlaylist,'''
overlay = '''        if (showKaraokeMode && currentItem != null) {
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
            currentPlaylist = currentPlaylist,'''
if 'onEnsureVocalRemovalEnabled = mainViewModel::ensureKaraokeVocalRemovalEnabled' not in s:
    if overlay_anchor not in s:
        raise SystemExit('Overlay anchor not found')
    s = s.replace(overlay_anchor, overlay, 1)

player.write_text(s)

# Karaoke title parsing fix for live sessions / TV shows / channel-branded uploads.
s = karaoke_lyrics.read_text()
if 'val titleIsProgramFormat' not in s:
    old_identity = '''            val artistTitle = splitArtistAndTitle(rawTitle)
            val inferredArtist = artistTitle?.first.orEmpty()
            val splitTitle = artistTitle?.second ?: rawTitle
            val coreTitle = stripAlternateTitle(splitTitle)
'''
    new_identity = '''            val artistTitle = splitArtistAndTitle(rawTitle)
            val titleIsProgramFormat = artistTitle != null && artistTitle.first.isBlank()
            val inferredArtist = artistTitle?.first.orEmpty()
            val splitTitle = artistTitle?.second ?: rawTitle
            val coreTitle = stripAlternateTitle(splitTitle)
'''
    if old_identity not in s:
        raise SystemExit('Karaoke title identity anchor not found')
    s = s.replace(old_identity, new_identity, 1)

    old_candidates = '''            val titleCandidates = linkedSetOf<String>().apply {
                add(coreTitle)
                add(splitTitle)
                add(rawTitle)
            }.filter { it.isNotBlank() }

            val artistCandidates = linkedSetOf<String>().apply {
                add(inferredArtist)
                add(uploaderArtist)
            }.filter { it.isNotBlank() }
'''
    new_candidates = '''            val titleCandidates = linkedSetOf<String>().apply {
                add(coreTitle)
                add(splitTitle)
                if (!titleIsProgramFormat) add(rawTitle)
            }.filter { it.isNotBlank() }

            val artistCandidates = linkedSetOf<String>().apply {
                add(inferredArtist)
                if (!titleIsProgramFormat) add(uploaderArtist)
            }.filter { it.isNotBlank() }
'''
    if old_candidates not in s:
        raise SystemExit('Karaoke title candidates anchor not found')
    s = s.replace(old_candidates, new_candidates, 1)

    old_split = '''    private fun splitArtistAndTitle(raw: String): Pair<String, String>? {
        val separators = listOf(" - ", " – ", " — ", " | ")
        separators.forEach { separator ->
            val index = raw.indexOf(separator)
            if (index > 0 && index < raw.length - separator.length) {
                val left = cleanArtist(raw.substring(0, index))
                val right = raw.substring(index + separator.length).trim()
                if (left.isNotBlank() && right.isNotBlank()) return left to right
            }
        }
        return null
    }
'''
    new_split = '''    private fun splitArtistAndTitle(raw: String): Pair<String, String>? {
        val separators = listOf(" - ", " – ", " — ", " | ")
        separators.forEach { separator ->
            val index = raw.indexOf(separator)
            if (index > 0 && index < raw.length - separator.length) {
                val leftRaw = raw.substring(0, index).trim()
                val right = raw.substring(index + separator.length).trim()

                // Uploads from TV shows / live-session channels commonly use:
                // "Song title - Program | Program Live Session".
                // In that format the left side is the TRACK, not the artist.
                if (looksLikeProgramSuffix(right)) {
                    val track = stripAlternateTitle(leftRaw)
                    if (track.isNotBlank()) return "" to track
                }

                val leftArtist = cleanArtist(leftRaw)
                if (leftArtist.isNotBlank() && right.isNotBlank()) return leftArtist to right
            }
        }
        return null
    }

    private fun looksLikeProgramSuffix(value: String): Boolean =
        Regex(
            "(?i)(live\\s*session|live\\s*performance|acoustic\\s*session|studio\\s*session|" +
                "\\bsession\\b|\\bepisode\\b|\\bep\\.?\\s*\\d*|\\bshow\\b|รายการ|4\\s*โพดำ)"
        ).containsMatchIn(value)
'''
    if old_split not in s:
        raise SystemExit('Karaoke splitArtistAndTitle anchor not found')
    s = s.replace(old_split, new_split, 1)

    old_music_video = '''    internal fun looksLikeMusicVideo(title: String): Boolean =
        Regex(
            "(?i)(\\bmv\\b|music\\s*video|official\\s*(mv|video)|official\\s*music\\s*video)"
        ).containsMatchIn(title)
'''
    new_music_video = '''    internal fun looksLikeMusicVideo(title: String): Boolean =
        Regex(
            "(?i)(\\bmv\\b|music\\s*video|official\\s*(mv|video)|official\\s*music\\s*video|" +
                "live\\s*session|live\\s*performance|acoustic\\s*session|studio\\s*session)"
        ).containsMatchIn(title)
'''
    if old_music_video not in s:
        raise SystemExit('Karaoke music-video anchor not found')
    s = s.replace(old_music_video, new_music_video, 1)

    karaoke_lyrics.write_text(s)

print('Karaoke Mode patch applied successfully')
