package com.nousresearch.hermesmobile.push

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Base64
import java.io.File

/** Pure rules for per-bot notification groups, file merging and the bot avatar. */
object PushGrouping {
    /** A file push this soon after a text push from the same bot joins that notification. */
    const val MERGE_WINDOW_MS = 10_000L

    fun groupKey(profile: String) = "hermes_bot_$profile"
    fun summaryTag(profile: String) = "summary:$profile"

    /** "Della · 2" */
    fun summaryText(profile: String, count: Int) = "${botName(profile)} · $count"
    fun botName(profile: String) = profile.replaceFirstChar { it.uppercase() }

    /** True when a file-only notice may fold into the text notice [lastAtMs] ago from the same bot. */
    fun shouldMerge(lastProfile: String?, lastAtMs: Long, profile: String, nowMs: Long): Boolean =
        profile.isNotBlank() && lastProfile == profile && nowMs - lastAtMs in 0..MERGE_WINDOW_MS

    /** Action button label for a pushed file. */
    fun fileLabel(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "apk" -> "Install"
        "pdf" -> "Open PDF"
        else -> "Open file"
    }

    // ---- avatar cache: the app saves each bot's avatar when it loads it; a push shows it as the large icon ----
    private fun avatarFile(context: Context, profile: String) =
        File(File(context.filesDir, "push_avatars"), profile.replace(Regex("[^A-Za-z0-9_-]"), "_"))

    /** Stores a gateway data URL (or removes the file when [dataUrl] is blank). */
    fun saveAvatar(context: Context, profile: String, dataUrl: String?) = runCatching {
        val f = avatarFile(context, profile)
        if (dataUrl.isNullOrBlank()) { f.delete(); return@runCatching }
        val bytes = Base64.decode(dataUrl.substringAfter("base64,", dataUrl), Base64.DEFAULT)
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
    }

    /** Round bot avatar, or a coloured circle with the bot's initial. */
    fun largeIcon(context: Context, profile: String, size: Int = 128): Bitmap {
        val photo = runCatching { avatarFile(context, profile).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) } }.getOrNull()
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (photo != null) {
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
            paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
            val side = minOf(photo.width, photo.height)
            val src = android.graphics.Rect((photo.width - side) / 2, (photo.height - side) / 2, (photo.width + side) / 2, (photo.height + side) / 2)
            canvas.drawBitmap(photo, src, android.graphics.Rect(0, 0, size, size), paint)
        } else {
            paint.color = android.graphics.Color.HSVToColor(floatArrayOf((profile.hashCode().and(0x7fffffff) % 360).toFloat(), 0.55f, 0.55f))
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
            paint.color = android.graphics.Color.WHITE
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = size * 0.5f
            paint.isFakeBoldText = true
            canvas.drawText(botName(profile).take(1), size / 2f, size / 2f - (paint.descent() + paint.ascent()) / 2, paint)
        }
        return out
    }
}
