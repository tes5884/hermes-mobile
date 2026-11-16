package com.nousresearch.hermesmobile.push

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.nousresearch.hermesmobile.MainActivity
import org.json.JSONObject
import java.io.File

/**
 * Pure rules for bot conversation notifications: which conversation (shortcut) a push belongs to, the bot's name in
 * the shade, and the bold heading line above the text. No Android types, so they are unit-tested.
 */
object PushConversation {
    /** Action of a tap on a bot's conversation shortcut or its group summary: open that bot's chat. */
    const val ACTION_OPEN_BOT = "hermes.bot"
    const val EXTRA_CONNECTION = "hermes.connection"
    const val EXTRA_PROFILE = "hermes.profile"

    /** One long-lived conversation shortcut per bot per server, so two servers' "ezra" never share one. */
    fun shortcutId(connection: String, profile: String): String = "bot:$connection|$profile"

    /** The bot's name as the bot list shows it; the profile name capitalised when the list never saved one. */
    fun botName(profile: String, savedLabel: String?): String =
        savedLabel?.trim()?.takeIf { it.isNotEmpty() } ?: PushGrouping.botName(profile)

    /**
     * The bold first line above the push text, or null. A routine's name, a channel's name or "Approval needed"
     * tell the user something; the bot's own name (already the sender), the generic "Hermes" and the canonical
     * chat's title ("Bot Chat") do not.
     */
    fun heading(title: String, botName: String, profile: String): String? {
        val t = title.trim()
        if (t.isEmpty()) return null
        val generic = setOf("hermes", "bot chat", botName.trim().lowercase(), profile.trim().lowercase())
        return t.takeUnless { it.lowercase() in generic }
    }

    /** True for the open-a-bot intent a conversation shortcut or a group summary sends. */
    fun opensBot(action: String?, connection: String?, profile: String?): Boolean =
        action == ACTION_OPEN_BOT && !connection.isNullOrBlank() && !profile.isNullOrBlank()

    /** What a shortcut tap hands to the view model: the same shape as a cron notice without a chat (opens Bot Chat). */
    fun openEvent(connection: String, profile: String): JSONObject =
        JSONObject().put("connection", connection).put("profile", profile)

    /**
     * The shortcut id to tie the notice to, or null when publishing it failed: `pushDynamicShortcut` answering
     * false (refused, e.g. rate-limited) counts as a failure, exactly like it throwing.
     */
    fun published(id: String, push: () -> Boolean): String? = runCatching { id.takeIf { push() } }.getOrNull()

    /**
     * The photo must ride as the large icon when Android won't draw it from a conversation shortcut: below
     * Android 11 (API 30), or when the shortcut wasn't published.
     */
    fun needsLargeIcon(sdkInt: Int, shortcutId: String?): Boolean = sdkInt < 30 || shortcutId == null
}

/**
 * The saved bot photos a push draws from (no network in the push path). The app writes one whenever it loads a
 * bot's avatar, and a roster load fetches every missing or stale one, judged by the `hermes-bots` ui_meta
 * revision the photo was saved under. Files live under one folder per server.
 */
object PushAvatars {
    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9_-]"), "_")
    private fun dir(context: Context, connection: String) = File(File(context.filesDir, "push_avatars"), "c_" + safe(connection))
    private fun photo(context: Context, connection: String, profile: String) = File(dir(context, connection), safe(profile))
    private fun meta(context: Context, connection: String, profile: String) = File(dir(context, connection), safe(profile) + ".json")
    /** Before vc87 the photo was saved per profile only; read it until the next roster load writes the new one. */
    private fun legacy(context: Context, profile: String) = File(File(context.filesDir, "push_avatars"), safe(profile))

    /** Saves a gateway data URL with the revision and bot name it came with; a blank URL removes the photo. */
    fun save(context: Context, connection: String, profile: String, dataUrl: String?, revision: Int?, label: String?) = runCatching {
        if (connection.isBlank() || profile.isBlank()) return@runCatching
        val f = photo(context, connection, profile)
        f.parentFile?.mkdirs()
        if (dataUrl.isNullOrBlank()) f.delete()
        else f.writeBytes(android.util.Base64.decode(dataUrl.substringAfter("base64,", dataUrl), android.util.Base64.DEFAULT))
        legacy(context, profile).delete()
        meta(context, connection, profile).writeText(JSONObject().put("revision", revision ?: -1)
            .put("label", label.orEmpty()).put("photo", !dataUrl.isNullOrBlank()).toString())
    }

    /** Saves only the bot's name (the photo and its revision stay). */
    fun saveLabel(context: Context, connection: String, profile: String, label: String) = runCatching {
        if (connection.isBlank() || profile.isBlank()) return@runCatching
        val m = meta(context, connection, profile)
        val json = runCatching { JSONObject(m.readText()) }.getOrDefault(JSONObject())
        if (json.optString("label") == label) return@runCatching
        m.parentFile?.mkdirs()
        m.writeText(json.put("label", label).toString())
    }

    /** The revision the saved photo (or saved "no photo") belongs to; null when nothing was saved for this server. */
    fun savedRevision(context: Context, connection: String, profile: String): Int? =
        runCatching { JSONObject(meta(context, connection, profile).readText()).optInt("revision", -1) }.getOrNull()

    fun savedLabel(context: Context, connection: String, profile: String): String? =
        runCatching { JSONObject(meta(context, connection, profile).readText()).optString("label") }.getOrNull()?.ifBlank { null }

    fun bitmap(context: Context, connection: String, profile: String): Bitmap? = runCatching {
        val f = photo(context, connection, profile).takeIf { it.exists() } ?: legacy(context, profile).takeIf { it.exists() }
        f?.let { android.graphics.BitmapFactory.decodeFile(it.path) }
    }.getOrNull()

    /**
     * True when the roster load must fetch this bot's photo: it has one and nothing is saved, or it was saved under
     * another `hermes-bots` revision (the photo may have changed on the desktop).
     */
    fun stale(saved: Int?, current: Int?, hasPhotoFile: Boolean, hasAvatar: Boolean): Boolean = when {
        !hasAvatar -> saved == null || hasPhotoFile
        saved == null || !hasPhotoFile -> true
        else -> current != null && saved != current
    }

    fun hasPhoto(context: Context, connection: String, profile: String): Boolean = photo(context, connection, profile).exists()
}

/** Android side: the bot as a notification [Person] and as a long-lived conversation shortcut. */
object PushPeople {
    private const val ICON_PX = 256

    /** The bot, with its round photo (or its initial on a colour) as the icon. */
    fun bot(context: Context, connection: String, profile: String): Person {
        val name = PushConversation.botName(profile, PushAvatars.savedLabel(context, connection, profile))
        return Person.Builder().setName(name).setKey(PushConversation.shortcutId(connection, profile)).setBot(true)
            .setIcon(IconCompat.createWithBitmap(icon(context, connection, profile))).build()
    }

    fun icon(context: Context, connection: String, profile: String, size: Int = ICON_PX): Bitmap =
        PushGrouping.roundAvatar(PushAvatars.bitmap(context, connection, profile), profile, size)

    /** The phone user, the other side of every bot conversation. */
    fun me(context: Context): Person =
        Person.Builder().setName(context.getString(com.nousresearch.hermesmobile.R.string.push_you)).setKey("me").build()

    /**
     * Publishes (or refreshes) the bot's conversation shortcut, so Android 11+ files the notice under Conversations
     * with the bot's photo large and the app's badge in the corner. Returns the shortcut id, or null on failure
     * (the notice then falls back to a large icon).
     */
    fun publish(context: Context, connection: String, profile: String, person: Person): String? {
        val id = PushConversation.shortcutId(connection, profile)
        return PushConversation.published(id) {
            val open = Intent(context, MainActivity::class.java).setAction(PushConversation.ACTION_OPEN_BOT)
                .putExtra(PushConversation.EXTRA_CONNECTION, connection).putExtra(PushConversation.EXTRA_PROFILE, profile)
            val shortcut = ShortcutInfoCompat.Builder(context, id)
                .setShortLabel(person.name ?: PushGrouping.botName(profile))
                .setLongLived(true)
                .setPerson(person)
                .setIcon(IconCompat.createWithBitmap(icon(context, connection, profile)))
                .setIntent(open)
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        }
    }

    /** Text with an optional bold heading line ("Morning briefing", "Front Office", "Approval needed"). */
    fun text(heading: String?, body: String): CharSequence {
        if (heading.isNullOrBlank()) return body
        val out = SpannableStringBuilder(heading)
        out.setSpan(StyleSpan(Typeface.BOLD), 0, heading.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (body.isNotBlank()) out.append('\n').append(body)
        return out
    }
}
