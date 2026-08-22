#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else '.').resolve()
vm = root / 'feature/main/src/main/java/com/example/main/MainViewModel.kt'
player = root / 'feature/main/src/main/java/com/example/main/components/bottomsheet/PlayerBottomSheet.kt'

for f in (vm, player):
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
print('Karaoke Mode patch applied successfully')
