package com.lumora.data.subtitle

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class ContentIdentity(
    val imdbId: String,
    val title: String,
    val year: Int? = null
)

class ContentMetadataService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun lookup(title: String, year: Int? = null, type: String): ContentIdentity? =
        withContext(Dispatchers.IO) {
            val cleaned = title
                .replace(Regex("""[\[\]()._-]+"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
            if (cleaned.isBlank()) return@withContext null

            val encoded = URLEncoder.encode(cleaned, "UTF-8")
            val endpoint = "https://v3-cinemeta.strem.io/catalog/$type/top/search=$encoded.json"
            val request = Request.Builder()
                .url(endpoint)
                .header("Accept", "application/json")
                .build()

            runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body?.string() ?: return@withContext null
                    val metas = JSONObject(body).optJSONArray("metas") ?: return@withContext null

                    val normalizedA = normalize(cleaned)
                    var best: ContentIdentity? = null
                    var bestScore = Int.MIN_VALUE

                    for (i in 0 until metas.length()) {
                        val meta = metas.optJSONObject(i) ?: continue
                        val id = meta.optString("id")
                        val name = meta.optString("name")
                        if (!id.startsWith("tt") || name.isBlank()) continue

                        val metaYear = meta.optString("year").toIntOrNull()
                            ?: meta.optString("releaseInfo").take(4).toIntOrNull()
                        val normalizedB = normalize(name)

                        val titleScore = when {
                            normalizedA == normalizedB -> 100
                            normalizedA.contains(normalizedB) || normalizedB.contains(normalizedA) -> 60
                            else -> similarityScore(normalizedA, normalizedB)
                        }
                        val yearScore = when {
                            year != null && metaYear == year -> 50
                            year != null && metaYear != null && kotlin.math.abs(metaYear - year) == 1 -> 15
                            else -> 0
                        }
                        val score = titleScore + yearScore

                        if (score > bestScore) {
                            bestScore = score
                            best = ContentIdentity(id, name, metaYear)
                        }
                    }

                    best?.takeIf { bestScore >= 45 }
                }
            }.getOrNull()
        }

    private fun normalize(value: String): String =
        value.lowercase()
            .replace(Regex("""[^\p{L}\p{N}\s]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun similarityScore(a: String, b: String): Int {
        val aWords = a.split(" ").filter { it.isNotBlank() }.toSet()
        val bWords = b.split(" ").filter { it.isNotBlank() }.toSet()
        if (aWords.isEmpty() || bWords.isEmpty()) return 0
        return (aWords.intersect(bWords).size * 100) / maxOf(aWords.size, bWords.size)
    }
}
