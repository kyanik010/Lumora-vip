package com.lumora

import android.app.AlertDialog
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.lumora.data.subtitle.ContentMetadataService
import com.lumora.data.subtitle.SubtitleService
import com.lumora.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun MainActivity.showSubtitlePicker() {
    val player = playerManager.getExoPlayer()
    val embedded = trackController.subtitleTracks(player)
    val channel = nowPlayingChannel
    val isVod = channel?.mediaType == MediaType.MOVIE || channel?.mediaType == MediaType.SERIES

    val labels = mutableListOf<String>()
    val actions = mutableListOf<() -> Unit>()

    if (isVod) {
        labels += "بحث عن ترجمة عربية"
        actions += { searchArabicSubtitle() }
    }

    if (embedded.isNotEmpty()) {
        labels += "إيقاف الترجمة"
        actions += { trackController.selectSubtitleTrack(player, null); showControls() }
        embedded.forEach { track ->
            labels += track.name
            actions += { trackController.selectSubtitleTrack(player, track.id); showControls() }
        }
    }

    if (labels.isEmpty()) {
        Toast.makeText(this, "لا توجد ترجمات لهذا المحتوى", Toast.LENGTH_SHORT).show()
        return
    }

    AlertDialog.Builder(this)
        .setTitle("الترجمة النصية")
        .setItems(labels.toTypedArray()) { dialog, which ->
            dialog.dismiss()
            actions[which]()
        }
        .setNegativeButton(getString(R.string.cancel), null)
        .let(::showControlsDialog)
}

private fun MainActivity.searchArabicSubtitle() {
    val channel = nowPlayingChannel ?: return
    if (channel.mediaType != MediaType.MOVIE && channel.mediaType != MediaType.SERIES) return

    val progress = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(40, 20, 40, 20)
        addView(ProgressBar(context))
        addView(TextView(context).apply {
            text = "  جاري البحث عن الترجمة العربية..."
            setTextColor(Color.WHITE)
        })
    }

    val dialog = AlertDialog.Builder(this)
        .setTitle("الترجمة النصية")
        .setView(progress)
        .setCancelable(true)
        .create()
    dialog.show()

    scope.launch {
        try {
            val identity = withContext(Dispatchers.IO) {
                val rawTitle = if (channel.mediaType == MediaType.SERIES) {
                    currentSeriesVersionContext?.first?.name ?: channel.name
                } else {
                    channel.name
                }
                val cleanedTitle = rawTitle
                    .replace(Regex("""(?i)\bS\d{1,2}E\d{1,3}\b"""), " ")
                    .substringBefore(" · ")
                    .trim()
                val year = Regex("""\b(19|20)\d{2}\b""")
                    .find(cleanedTitle)?.value?.toIntOrNull()

                ContentMetadataService().lookup(
                    title = cleanedTitle,
                    year = year,
                    type = if (channel.mediaType == MediaType.SERIES) "series" else "movie"
                )
            } ?: throw IllegalStateException("لم يتم التعرف على الفيلم أو المسلسل")

            val seasonEpisode = if (channel.mediaType == MediaType.SERIES) {
                val match = Regex("""(?i)\bS(\d{1,2})E(\d{1,3})\b""").find(channel.name)
                val season = match?.groupValues?.getOrNull(1)?.toIntOrNull()
                val episode = match?.groupValues?.getOrNull(2)?.toIntOrNull() ?: channel.episodeNum
                season to episode
            } else null to null

            if (channel.mediaType == MediaType.SERIES &&
                (seasonEpisode.first == null || seasonEpisode.second == null)
            ) {
                throw IllegalStateException("تعذر تحديد الموسم والحلقة")
            }

            val subtitleService = SubtitleService(this@searchArabicSubtitle)
            val candidates = subtitleService.searchArabic(
                imdbId = identity.imdbId,
                season = seasonEpisode.first,
                episode = seasonEpisode.second
            )
            val candidate = candidates.firstOrNull()
                ?: throw IllegalStateException("لا توجد ترجمة عربية متاحة")

            val file = subtitleService.downloadToCache(candidate)
            val attached = playerManager.attachExternalSubtitleFile(
                file.absolutePath,
                candidate.label,
                "ar"
            )
            if (!attached) throw IllegalStateException("تعذر تشغيل الترجمة")

            withContext(Dispatchers.Main) {
                dialog.dismiss()
                Toast.makeText(this@searchArabicSubtitle, "تم تشغيل الترجمة العربية", Toast.LENGTH_SHORT).show()
                showControls()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                dialog.dismiss()
                Toast.makeText(
                    this@searchArabicSubtitle,
                    e.message ?: "تعذر الحصول على الترجمة",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
