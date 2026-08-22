package com.example.main.components.karaoke

import android.net.Uri
import com.example.domain.model.playable.PlayableItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal data class KaraokeLyricLine(
    val timeMs: Long,
    val text: String,
)

internal sealed interface KaraokeLyricsResult {
    data object Loading : KaraokeLyricsResult
    data class Synced(val lines: List<KaraokeLyricLine>) : KaraokeLyricsResult
    data class Plain(val text: String) : KaraokeLyricsResult
    data class Error(val message: String) : KaraokeLyricsResult
}

internal object KaraokeLyricsRepository {
    suspend fun get(item: PlayableItem): KaraokeLyricsResult = withContext(Dispatchers.IO) {
        runCatching {
            val title = cleanTitle(item.title)
            val artist = when (item) {
                is PlayableItem.Remote -> item.video.uploaderName.orEmpty()
                is PlayableItem.Local -> item.artist.orEmpty()
            }.let(::cleanArtist)
            val durationSec = when {
                item.duration <= 0L -> 0L
                item.duration > 100_000L -> item.duration / 1000L
                else -> item.duration
            }

            val searchUrl = buildString {
                append("https://lrclib.net/api/search?track_name=")
                append(Uri.encode(title))
                if (artist.isNotBlank()) {
                    append("&artist_name=")
                    append(Uri.encode(artist))
                }
            }
            val json = httpGet(searchUrl)
            val results = JSONArray(json)
            if (results.length() == 0) {
                return@runCatching KaraokeLyricsResult.Error("ไม่พบเนื้อเพลง")
            }

            val best = chooseBest(results, durationSec)
            val synced = best.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
            val plain = best.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }

            when {
                synced != null -> {
                    val parsed = LrcParser.parse(synced)
                    if (parsed.isNotEmpty()) KaraokeLyricsResult.Synced(parsed)
                    else if (plain != null) KaraokeLyricsResult.Plain(plain)
                    else KaraokeLyricsResult.Error("เนื้อเพลงนี้ไม่มีเวลา sync")
                }
                plain != null -> KaraokeLyricsResult.Plain(plain)
                else -> KaraokeLyricsResult.Error("ไม่พบเนื้อเพลง")
            }
        }.getOrElse {
            KaraokeLyricsResult.Error("โหลดเนื้อเพลงไม่สำเร็จ")
        }
    }

    private fun httpGet(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("User-Agent", "Transpose-Karaoke/1.0")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (connection.responseCode !in 200..299) {
                error("LRCLIB HTTP ${connection.responseCode}")
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun chooseBest(results: JSONArray, durationSec: Long): JSONObject {
        var best = results.getJSONObject(0)
        var bestScore = Long.MAX_VALUE
        for (i in 0 until results.length()) {
            val candidate = results.getJSONObject(i)
            val candidateDuration = candidate.optDouble("duration", 0.0).toLong()
            val hasSynced = candidate.optString("syncedLyrics").isNotBlank()
            val durationDelta = if (durationSec > 0 && candidateDuration > 0) {
                kotlin.math.abs(candidateDuration - durationSec)
            } else 60L
            val score = durationDelta + if (hasSynced) 0 else 10_000
            if (score < bestScore) {
                best = candidate
                bestScore = score
            }
        }
        return best
    }

    private fun cleanTitle(raw: String): String = raw
        .replace(Regex("(?i)\\s*[\\[(].*?(official|lyrics?|audio|mv|music video|karaoke).*?[\\])]"), "")
        .replace(Regex("(?i)\\s*[-|:]\\s*(official.*|lyrics?.*|audio.*|mv.*)$"), "")
        .trim()

    private fun cleanArtist(raw: String): String = raw
        .replace(Regex("(?i)\\s*[-–]\\s*topic$"), "")
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
                add(KaraokeLyricLine((minute * 60_000L) + (second * 1_000L) + fractionMs, text))
            }
        }
    }.sortedBy { it.timeMs }
}
