#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else '.').resolve()
lyrics = root / 'feature/main/src/main/java/com/example/main/components/karaoke/KaraokeLyrics.kt'

if not lyrics.exists():
    raise SystemExit(f'Missing expected file: {lyrics}')

s = lyrics.read_text()
changed = False

# Live-session / TV-show uploads often use:
#   "Song title - Program | Program Live Session"
# In that shape, the left side is the track title, not the artist.
if 'val titleIsProgramFormat' not in s:
    old = '''            val artistTitle = splitArtistAndTitle(rawTitle)
            val inferredArtist = artistTitle?.first.orEmpty()
            val splitTitle = artistTitle?.second ?: rawTitle
            val coreTitle = stripAlternateTitle(splitTitle)
'''
    new = '''            val artistTitle = splitArtistAndTitle(rawTitle)
            val titleIsProgramFormat = artistTitle != null && artistTitle.first.isBlank()
            val inferredArtist = artistTitle?.first.orEmpty()
            val splitTitle = artistTitle?.second ?: rawTitle
            val coreTitle = stripAlternateTitle(splitTitle)
'''
    if old in s:
        s = s.replace(old, new, 1)
        changed = True
    else:
        print('WARN: title identity block already changed; skipping')

if 'if (!titleIsProgramFormat) add(rawTitle)' not in s:
    old = '''            val titleCandidates = linkedSetOf<String>().apply {
                add(coreTitle)
                add(splitTitle)
                add(rawTitle)
            }.filter { it.isNotBlank() }

            val artistCandidates = linkedSetOf<String>().apply {
                add(inferredArtist)
                add(uploaderArtist)
            }.filter { it.isNotBlank() }
'''
    new = '''            val titleCandidates = linkedSetOf<String>().apply {
                add(coreTitle)
                add(splitTitle)
                if (!titleIsProgramFormat) add(rawTitle)
            }.filter { it.isNotBlank() }

            val artistCandidates = linkedSetOf<String>().apply {
                add(inferredArtist)
                if (!titleIsProgramFormat) add(uploaderArtist)
            }.filter { it.isNotBlank() }
'''
    if old in s:
        s = s.replace(old, new, 1)
        changed = True
    else:
        print('WARN: title candidate block already changed; skipping')

if 'private fun looksLikeProgramSuffix' not in s:
    start = s.find('    private fun splitArtistAndTitle(raw: String): Pair<String, String>? {')
    end = s.find('    private fun stripYouTubeNoise', start)
    if start >= 0 and end > start:
        replacement = '''    private fun splitArtistAndTitle(raw: String): Pair<String, String>? {
        val separators = listOf(" - ", " – ", " — ", " | ")
        separators.forEach { separator ->
            val index = raw.indexOf(separator)
            if (index > 0 && index < raw.length - separator.length) {
                val leftRaw = raw.substring(0, index).trim()
                val right = raw.substring(index + separator.length).trim()

                // TV shows and live-session channels usually put the real song title first.
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
                "\\bsession\\b|\\bepisode\\b|\\bep\\.?\\s*\\d*|\\bshow\\b|รายการ|" +
                "4\\s*โพดำ|workpoint|the\\s*voice|masked\\s*singer)"
        ).containsMatchIn(value)

'''
        s = s[:start] + replacement + s[end:]
        changed = True
    else:
        print('WARN: splitArtistAndTitle block not found; skipping')

# Treat live sessions like music videos so YouTube timed captions are eligible
# and MV-aware offset handling can be used as a fallback.
music_start = s.find('    internal fun looksLikeMusicVideo(title: String): Boolean =')
music_end = s.find('    private fun durationSeconds', music_start)
if music_start >= 0 and music_end > music_start:
    replacement = '''    internal fun looksLikeMusicVideo(title: String): Boolean =
        Regex(
            "(?i)(\\bmv\\b|music\\s*video|official\\s*(mv|video)|official\\s*music\\s*video|" +
                "live\\s*session|live\\s*performance|acoustic\\s*session|studio\\s*session)"
        ).containsMatchIn(title)

'''
    current = s[music_start:music_end]
    if current != replacement:
        s = s[:music_start] + replacement + s[music_end:]
        changed = True
else:
    print('WARN: looksLikeMusicVideo block not found; skipping')

if changed:
    lyrics.write_text(s)
    print('Karaoke title parser patch applied')
else:
    print('Karaoke title parser already up to date')
