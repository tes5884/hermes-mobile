package com.nousresearch.hermesmobile.push

/**
 * "Only when <bot> needs you" (Bot details → Settings → Notifications, Nova's mock 4b). For a quiet bot only the
 * events that need the user ring: an approval waiting, a task that failed, a reminder the user set, and the pairing
 * test. Finished turns, routine posts and the rest still land in the notification inbox, silently, the way the
 * "only phone turns" rule already treats a turn started elsewhere. A bot that is not quiet rings exactly as before.
 */
object PushQuiet {
    val RINGS = setOf("approval_required", "turn_failed", "reminder", "test")

    fun rings(eventType: String, quiet: Boolean): Boolean = !quiet || eventType in RINGS

    fun key(connection: String, profile: String): String = "$connection|$profile"
}
