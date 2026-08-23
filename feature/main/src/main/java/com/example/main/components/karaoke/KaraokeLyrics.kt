package com.example.main.components.karaoke

import android.net.Uri
import com.example.domain.model.playable.PlayableItem
import com.example.domain.model.youtube.video_detail.SubtitleTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

internal data class KaraokeLyricLine(
    val timeMs: Long,
    val text: String,
)

internal sealed interface KaraokeLyricsResult {
    data object Loading : KaraokeLyricsResult

    data class Synced(
        val lines: List<KaraokeLyricLine>,
        val sourceLabel: String = "LRCLIB",
        val autoOffsetMs: Long = 0L,
    ) : KaraokeLyricsResult

    data class Plain(
        val text: String,
        val sourceLabel: String = "YouTube description",
    ) : KaraokeLyricsResult

    data class Error(val message: String) : KaraokeLyricsResult
}

internal object KaraokeLyricsRepository {
    suspend fun get(item: PlayableItem): KaraokeLyricsResult = withContext(Dispatchers.IO) {
        val descriptionFallback = extractLyricsFromDescription(item)

        runCatching {
            val rawTitle = stripYouTubeNoise(item.title)
            val uploaderArtist = when (item) {
                is PlayableItem.Remote -> item.video.uploaderName.orEmpty()
                is PlayableItem.Local -> item.artist.orEmpty()
            }.let(::cleanArtist)

            val artistTitle = splitArtistAndTitle(rawTitle)
            val inferredArtist = artistTitle?.first.orEmpty()
            val splitTitle = artistTitle?.second ?: rawTitle
            val coreTitle = stripAlternateTitle(splitTitle)

            val videoDurationSec = durationSeconds(item.duration)

            val titleCandidates = linkedSetOf<String>().apply {
                add(coreTitle)
                add(splitTitle)
                add(rawTitle)
            }.filter { it.isNotBlank() }

            val artistCandidates = linkedSetOf<String>().apply {
                add(inferredArtist)
                add(uploaderArtist)
            }.filter { it.isNotBlank() }

            val urls = buildSearchUrls(titleCandidates, artistCandidates)
            val merged = LinkedHashMap<Long, JSONObject>()

            for ((index, url) in urls.withIndex()) {
                val results = JSONArray(httpGetText(url, "application/json"))
                for (i in 0 until results.length()) {
                    val candidate = results.getJSONObject(i)
                    val id = candidate.optLong("id", Long.MIN_VALUE + merged.size)
                    merged.putIfAbsent(id, candidate)
                }

                if (merged.size >= 8) break
                if (index < urls.lastIndex) delay(250)
            }

            if (merged.isEmpty()) {
                return@runCatching descriptionFallback?.let {
                    KaraokeLyricsResult.Plain(it, "YouTube description")
                } ?: KaraokeLyricsResult.Error(
                    "ไม่พบเนื้อเพลงใน LRCLIB หรือคำอธิบาย YouTube\nค้นหา: $coreTitle"
                )
            }

            val best = chooseBest(
                results = merged.values.toList(),
                wantedTitles = titleCandidates,
                wantedArtists = artistCandidates,
                durationSec = videoDurationSec,
            )

            if (best == null) {
                return@runCatching descriptionFallback?.let {
                    KaraokeLyricsResult.Plain(it, "YouTube description")
                } ?: KaraokeLyricsResult.Error(
                    "LRCLIB พบผลค้นหา แต่ไม่ตรงกับเพลงนี้\nกำลังใช้แหล่ง YouTube แทน"
                )
            }

            val synced = best.optString("syncedLyrics")
                .takeIf { it.isNotBlank() && it != "null" }
            val plain = best.optString("plainLyrics")
                .takeIf { it.isNotBlank() && it != "null" }
            val lrclibDurationSec = best.optDouble("duration", 0.0).toLong()
            val autoOffsetMs = calculateMvIntroOffsetMs(
                item = item,
                videoDurationSec = videoDurationSec,
                trackDurationSec = lrclibDurationSec,
            )

            when {
                synced != null -> {
                    val parsed = LrcParser.parse(synced)
                    when {
                        parsed.isNotEmpty() -> KaraokeLyricsResult.Synced(
                            lines = parsed,
                            sourceLabel = "LRCLIB",
                            autoOffsetMs = autoOffsetMs,
                        )
                        plain != null -> KaraokeLyricsResult.Plain(plain, "LRCLIB")
                        descriptionFallback != null -> KaraokeLyricsResult.Plain(
                            descriptionFallback,
                            "YouTube description",
                        )
                        else -> KaraokeLyricsResult.Error("เนื้อเพลงนี้ไม่มีเวลา sync")
                    }
                }
                plain != null -> KaraokeLyricsResult.Plain(plain, "LRCLIB")
                descriptionFallback != null -> KaraokeLyricsResult.Plain(
                    descriptionFallback,
                    "YouTube description",
                )
                else -> KaraokeLyricsResult.Error("ไม่พบเนื้อเพลง")
            }
        }.getOrElse {
            descriptionFallback?.let {
                KaraokeLyricsResult.Plain(it, "YouTube description")
            } ?: KaraokeLyricsResult.Error("โหลดเนื้อเพลงไม่สำเร็จ")
        }
    }

    suspend fun fromYouTubeSubtitles(
        tracks: List<SubtitleTrack>,
        videoTitle: String,
    ): KaraokeLyricsResult.Synced? = withContext(Dispatchers.IO) {
        if (tracks.isEmpty()) return@withContext null

        val looksLikeMv = looksLikeMusicVideo(videoTitle)
        val thaiTitle = Regex("[ก-๙]").containsMatchIn(videoTitle)
        val orderedTracks = tracks
            .filter { !it.autoGenerated || looksLikeMv }
            .sortedWith(
                compareBy<SubtitleTrack> { track ->
                    val lang = track.languageTag.lowercase()
                    when {
                        thaiTitle && lang.startsWith("th") -> 0
                        !thaiTitle && lang.startsWith("en") -> 0
                        lang.startsWith("th") || lang.startsWith("en") -> 1
                        else -> 2
                    }
                }.thenBy { if (it.autoGenerated) 1 else 0 }
            )

        for (track in orderedTracks.take(4)) {
            val parsed = runCatching {
                val webVtt = httpGetText(track.url, "text/vtt,text/plain,*/*")
                WebVttParser.parse(webVtt)
            }.getOrNull().orEmpty()

            if (parsed.size >= 4) {
                val autoLabel = if (track.autoGenerated) " auto" else ""
                return@withContext KaraokeLyricsResult.Synced(
                    lines = parsed,
                    sourceLabel = "YouTube captions ${track.languageTag}$autoLabel",
                    autoOffsetMs = 0L,
                )
            }
        }

        null
    }

    internal fun fromDescription(description: String): KaraokeLyricsResult? =
        extractLyricsFromRawDescription(description)
            ?.let { KaraokeLyricsResult.Plain(it, "YouTube description") }

    internal fun looksLikeMusicVideo(title: String): Boolean =
        Regex(
            "(?i)(\\bmv\\b|music\\s*video|official\\s*(mv|video)|official\\s*music\\s*video)"
        ).containsMatchIn(title)

    private fun durationSeconds(duration: Long): Long = when {
        duration <= 0L -> 0L
        duration > 100_000L -> duration / 1000L
        else -> duration
    }

    private fun calculateMvIntroOffsetMs(
        item: PlayableItem,
        videoDurationSec: Long,
        trackDurationSec: Long,
    ): Long {
        if (item !is PlayableItem.Remote) return 0L
        if (!looksLikeMusicVideo(item.title)) return 0L
        if (videoDurationSec <= 0L || trackDurationSec <= 0L) return 0L

        val extraSeconds = videoDurationSec - trackDurationSec
        return if (extraSeconds in 2L..45L) {
            extraSeconds * 1_000L
        } else {
            0L
        }
    }

    private fun buildSearchUrls(
        titles: List<String>,
        artists: List<String>,
    ): List<String> {
        val urls = linkedSetOf<String>()
        val primaryTitle = titles.firstOrNull().orEmpty()
        val primaryArtist = artists.firstOrNull().orEmpty()

        if (primaryTitle.isNotBlank() && primaryArtist.isNotBlank()) {
            urls += structuredSearch(primaryTitle, primaryArtist)
        }

        titles.take(2).forEach { title ->
            artists.take(2).forEach { artist ->
                urls += structuredSearch(title, artist)
            }
        }

        titles.take(2).forEach { title ->
            urls += structuredSearch(title, null)
        }

        if (primaryTitle.isNotBlank() && primaryArtist.isNotBlank()) {
            urls += keywordSearch("$primaryArtist $primaryTitle")
        }
        if (primaryTitle.isNotBlank()) {
            urls += keywordSearch(primaryTitle)
        }

        return urls.take(8)
    }

    private fun structuredSearch(title: String, artist: String?): String = buildString {
        append("https://lrclib.net/api/search?track_name=")
        append(Uri.encode(title))
        if (!artist.isNullOrBlank()) {
            append("&artist_name=")
            append(Uri.encode(artist))
        }
    }

    private fun keywordSearch(query: String): String =
        "https://lrclib.net/api/search?q=${Uri.encode(query)}"

    private fun httpGetText(url: String, accept: String): String {
        var attempt = 0
        while (true) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    "Transpose-Karaoke/1.0 (https://github.com/poomkart/Transpose)"
                )
                setRequestProperty("Accept", accept)
            }

            try {
                val responseCode = connection.responseCode
                if (responseCode == 429 && attempt == 0) {
                    val retrySeconds = connection.getHeaderField("Retry-After")
                        ?.toLongOrNull()
                        ?.coerceIn(1L, 3L)
                        ?: 1L
                    attempt += 1
                    Thread.sleep(retrySeconds * 1_000L)
                    continue
                }
                if (responseCode !in 200..299) {
                    error("HTTP $responseCode")
                }
                return connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun chooseBest(
        results: List<JSONObject>,
        wantedTitles: List<String>,
        wantedArtists: List<String>,
        durationSec: Long,
    ): JSONObject? {
        val confidentResults = results.filter { candidate ->
            val candidateTitle = candidate.optString("trackName")
                .ifBlank { candidate.optString("name") }
            val candidateArtist = candidate.optString("artistName")

            isConfidentTitleMatch(wantedTitles, candidateTitle) &&
                isConfidentArtistMatch(wantedArtists, candidateArtist)
        }

        return confidentResults.minByOrNull { candidate ->
            val candidateTitle = candidate.optString("trackName")
                .ifBlank { candidate.optString("name") }
            val candidateArtist = candidate.optString("artistName")
            val candidateDuration = candidate.optDouble("duration", 0.0).toLong()
            val synced = candidate.optString("syncedLyrics")
                .takeIf { it.isNotBlank() && it != "null" }
            val plain = candidate.optString("plainLyrics")
                .takeIf { it.isNotBlank() && it != "null" }

            val titlePenalty = wantedTitles.minOfOrNull {
                textDistancePenalty(normalize(it), normalize(candidateTitle))
            } ?: 1_000

            val artistPenalty = if (wantedArtists.isEmpty()) {
                80
            } else {
                wantedArtists.minOfOrNull {
                    textDistancePenalty(normalize(it), normalize(candidateArtist))
                } ?: 300
            }

            val durationPenalty = if (durationSec > 0 && candidateDuration > 0) {
                abs(candidateDuration - durationSec).coerceAtMost(300L).toInt()
            } else {
                30
            }

            val lyricsPenalty = when {
                synced != null -> 0
                plain != null -> 120
                else -> 2_000
            }

            (titlePenalty * 3) + artistPenalty + durationPenalty + lyricsPenalty
        }
    }

    private fun isConfidentTitleMatch(wantedTitles: List<String>, actualTitle: String): Boolean {
        val actual = normalize(actualTitle)
        if (actual.isBlank()) return false

        return wantedTitles.any { wanted ->
            val expected = normalize(wanted)
            expected.isNotBlank() && (
                expected == actual ||
                    actual.contains(expected) ||
                    expected.contains(actual)
                )
        }
    }

    private fun isConfidentArtistMatch(wantedArtists: List<String>, actualArtist: String): Boolean {
        if (wantedArtists.isEmpty()) return true

        val actual = normalize(actualArtist)
        if (actual.isBlank()) return false

        return wantedArtists.any { wanted ->
            val expected = normalize(wanted)
            expected.isNotBlank() && (
                expected == actual ||
                    actual.contains(expected) ||
                    expected.contains(actual)
                )
        }
    }

    private fun extractLyricsFromDescription(item: PlayableItem): String? {
        val description = (item as? PlayableItem.Remote)
            ?.video
            ?.description
            .orEmpty()
        return extractLyricsFromRawDescription(description)
    }

    private fun extractLyricsFromRawDescription(description: String): String? {
        if (description.isBlank()) return null

        val lines = description.lines()
        val markerRegex = Regex("(?i)^(lyrics?|เนื้อเพลง)\\s*[:：]?\\s*$")
        val markerIndex = lines.indexOfFirst { markerRegex.matches(it.trim()) }
        if (markerIndex < 0) return null

        val collected = mutableListOf<String>()
        for (rawLine in lines.drop(markerIndex + 1)) {
            val line = rawLine.trim()

            if (collected.isNotEmpty() && isDescriptionStopLine(line)) {
                break
            }

            if (line.isBlank()) {
                if (collected.isNotEmpty() && collected.last().isNotBlank()) {
                    collected += ""
                }
                continue
            }

            collected += line
        }

        val text = collected
            .dropLastWhile { it.isBlank() }
            .joinToString("\n")
            .trim()

        val nonBlankLineCount = text.lineSequence().count { it.isNotBlank() }
        return text.takeIf { nonBlankLineCount >= 4 }
    }

    private fun isDescriptionStopLine(line: String): Boolean {
        if (line.isBlank()) return false
        if (line.matches(Regex("^[-=_]{4,}.*$"))) return true
        if (line.startsWith("#")) return true

        return Regex(
            "(?i)^(follow|ติดตาม|contact|ติดต่องาน|digital\\s+released|stream|listen|available|" +
                "credits?|facebook|instagram|tiktok|youtube|x\\s*:|www\\.|https?://).*$"
        ).matches(line)
    }

    private fun textDistancePenalty(wanted: String, actual: String): Int {
        if (wanted.isBlank() || actual.isBlank()) return 600
        if (wanted == actual) return 0
        if (actual.contains(wanted) || wanted.contains(actual)) return 35

        val wantedTokens = wanted.split(' ').filter { it.isNotBlank() }.toSet()
        val actualTokens = actual.split(' ').filter { it.isNotBlank() }.toSet()
        if (wantedTokens.isEmpty() || actualTokens.isEmpty()) return 500

        val overlap = wantedTokens.intersect(actualTokens).size
        val total = wantedTokens.union(actualTokens).size.coerceAtLeast(1)
        return 100 + ((1f - overlap.toFloat() / total) * 400f).toInt()
    }

    private fun normalize(value: String): String = value
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun splitArtistAndTitle(raw: String): Pair<String, String>? {
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

    private fun stripYouTubeNoise(raw: String): String = raw
        .replace(
            Regex(
                "(?i)\\s*[\\[(](official\\s*)?(music\\s*)?(video|mv|audio|lyric\\s*video|lyrics?|visualizer|live|performance|4k|hd).*?[\\])]"
            ),
            ""
        )
        .replace(
            Regex(
                "(?i)\\s*[-|:]\\s*((official\\s*)?(music\\s*)?(video|mv|audio|lyrics?|visualizer|4k|hd).*)$"
            ),
            ""
        )
        .replace(Regex("(?i)\\s*#\\S+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun stripAlternateTitle(raw: String): String = raw
        .replace(Regex("\\s*[\\[(][^\\])]{1,60}[\\])]\\s*$"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun cleanArtist(raw: String): String = raw
        .replace(Regex("(?i)\\s*[-–]\\s*topic$"), "")
        .replace(Regex("(?i)\\s*(official|records?|music|channel)\\s*$"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}

internal object LrcParser {
    private val timestamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")

    fun parse(lrc: String): List<KaraokeLyricLine> = buildList {
        lrc.lineSequence().forEach { rawLine ->
            val matches = timestamp.findAll(rawLine).toList()
            if (matches.isEmpty()) return@forEach
            val text = timestamp.replace(rawLine, "").trim()
            if (text.isBlank()) return@forEach
            matches.forEach { match ->
                val minute = match.groupValues[1].toLongOrNull() ?: 0L
                val second = match.groupValues[2].toLongOrNull() ?: 0L
                val fractionRaw = match.groupValues[3]
                val fractionMs = when (fractionRaw.length) {
                    1 -> fractionRaw.toLongOrNull()?.times(100) ?: 0L
                    2 -> fractionRaw.toLongOrNull()?.times(10) ?: 0L
                    3 -> fractionRaw.toLongOrNull() ?: 0L
                    else -> 0L
                }
                add(
                    KaraokeLyricLine(
                        (minute * 60_000L) + (second * 1_000L) + fractionMs,
                        text,
                    )
                )
            }
        }
    }.sortedBy { it.timeMs }
}

internal object WebVttParser {
    private val timestampLine = Regex(
        "^(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})[.,](\\d{1,3})(?:\\s+.*)?$"
    )

    fun parse(webVtt: String): List<KaraokeLyricLine> {
        val sourceLines = webVtt
            .replace("\\r\\n", "\\n")
            .replace('\\r', '\\n')
            .lines()

        val result = mutableListOf<KaraokeLyricLine>()
        var index = 0

        while (index < sourceLines.size) {
            val line = sourceLines[index].trim()
            if (!line.contains("-->")) {
                index += 1
                continue
            }

            val startTime = parseTimestamp(line.substringBefore("-->").trim())
            index += 1

            val textParts = mutableListOf<String>()
            while (index < sourceLines.size && sourceLines[index].isNotBlank()) {
                textParts += sourceLines[index]
                index += 1
            }

            if (startTime == null) continue
            val text = cleanCueText(textParts.joinToString(" "))
            if (text.isBlank()) continue

            val previous = result.lastOrNull()
            if (
                previous == null ||
                previous.text != text ||
                abs(previous.timeMs - startTime) > 1_000L
            ) {
                result += KaraokeLyricLine(startTime, text)
            }
        }

        return result.sortedBy { it.timeMs }
    }

    private fun parseTimestamp(raw: String): Long? {
        val match = timestampLine.matchEntire(raw.trim()) ?: return null
        val hours = match.groupValues[1].toLongOrNull() ?: 0L
        val minutes = match.groupValues[2].toLongOrNull() ?: return null
        val seconds = match.groupValues[3].toLongOrNull() ?: return null
        val fractionRaw = match.groupValues[4]
        val milliseconds = when (fractionRaw.length) {
            1 -> fractionRaw.toLongOrNull()?.times(100L)
            2 -> fractionRaw.toLongOrNull()?.times(10L)
            else -> fractionRaw.padEnd(3, '0').take(3).toLongOrNull()
        } ?: return null

        return (hours * 3_600_000L) +
            (minutes * 60_000L) +
            (seconds * 1_000L) +
            milliseconds
    }

    private fun cleanCueText(raw: String): String = raw
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
