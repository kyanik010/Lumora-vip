package com.lumora.data.subtitle

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class SubtitleCandidate(
    val id: String,
    val url: String,
    val language: String,
    val label: String
)

class SubtitleService(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun searchArabic(
        imdbId: String,
        season: Int? = null,
        episode: Int? = null
    ): List<SubtitleCandidate> = withContext(Dispatchers.IO) {
        val videoId = if (season != null && episode != null) {
            "$imdbId:$season:$episode"
        } else {
            imdbId
        }
        val type = if (season != null && episode != null) "series" else "movie"
        val url = "https://opensubtitles-v3.strem.io/subtitles/$type/$videoId.json"

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("OpenSubtitles HTTP \${response.code}")
            }

            val body = response.body?.string().orEmpty()
            val subtitles = JSONObject(body).optJSONArray("subtitles")
                ?: return@withContext emptyList()

            buildList {
                for (i in 0 until subtitles.length()) {
                    val item = subtitles.optJSONObject(i) ?: continue
                    val itemUrl = item.optString("url")
                    if (itemUrl.isBlank()) continue

                    val lang = item.optString("lang").lowercase()
                    if (lang != "ara" && lang != "ar" && !lang.startsWith("ar-")) continue

                    add(
                        SubtitleCandidate(
                            id = item.optString("id").ifBlank { "ara_$i" },
                            url = itemUrl,
                            language = "ar",
                            label = item.optString("label").ifBlank { "العربية" }
                        )
                    )
                }
            }
        }
    }

    suspend fun downloadToCache(candidate: SubtitleCandidate): File =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(candidate.url)
                .header("Accept", "text/plain, text/vtt, application/x-subrip, */*")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("تعذر تحميل الترجمة: HTTP \${response.code}")
                }

                val bytes = response.body?.bytes()
                    ?: throw IllegalStateException("ملف الترجمة فارغ")

                val dir = File(context.cacheDir, "subtitles").apply { mkdirs() }
                val safeId = candidate.id.replace(Regex("[^A-Za-z0-9_-]"), "_")
                val extension = candidate.url.substringBefore('?').substringAfterLast('.', "srt")
                    .lowercase()
                    .let { if (it in setOf("srt", "vtt", "ass", "ssa")) it else "srt" }
                val file = File(dir, "arabic_\${safeId}.\$extension")
                file.writeBytes(bytes)
                file
            }
        }
}
