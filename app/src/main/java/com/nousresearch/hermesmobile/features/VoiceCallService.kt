package com.nousresearch.hermesmobile.features

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nousresearch.hermesmobile.HermesApplication
import com.nousresearch.hermesmobile.MainActivity
import com.nousresearch.hermesmobile.R

/**
 * Keeps a live call going with the screen off or another app in front: a foreground service of type `microphone`
 * (Android only lets the mic record in the background from one), an ongoing "On a call with <bot>" notice with
 * Hang up, and a partial wake lock so the CPU keeps feeding the mic and the player. Started when the call starts
 * (from the call button, so the app is in front, as Android 14 requires) and stopped when it ends.
 */
class VoiceCallService : Service() {
    companion object {
        private const val CHANNEL = "hermes_call"
        private const val NOTICE_ID = 4207
        private const val ACTION_HANG_UP = "hermes.call.hang_up"
        const val EXTRA_LABEL = "label"
        const val EXTRA_CONNECTION = "connection"
        const val EXTRA_PROFILE = "profile"

        fun start(context: Context, label: String, connection: String, profile: String) {
            val intent = Intent(context, VoiceCallService::class.java).putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_CONNECTION, connection).putExtra(EXTRA_PROFILE, profile)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) { context.stopService(Intent(context, VoiceCallService::class.java)) }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HANG_UP) {
            (application as HermesApplication).viewModel.hangUpCall()
            return START_NOT_STICKY
        }
        val label = intent?.getStringExtra(EXTRA_LABEL).orEmpty().ifBlank { "Hermes" }
        val connection = intent?.getStringExtra(EXTRA_CONNECTION).orEmpty()
        val profile = intent?.getStringExtra(EXTRA_PROFILE).orEmpty()
        val notice = notice(label, connection, profile)
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        try {
            ServiceCompat.startForeground(this, NOTICE_ID, notice, type)
        } catch (e: Exception) {
            // Android refused (no mic permission, or not allowed from here): the call can't run in the background.
            android.util.Log.w("HermesCall", "foreground service refused: ${e.message}")
            (application as HermesApplication).viewModel.hangUpCall()
            stopSelf()
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hermes:voice-call").apply { setReferenceCounted(false); acquire(4 * 60 * 60 * 1000L) }
        }
        return START_NOT_STICKY
    }

    private fun notice(label: String, connection: String, profile: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.call_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 40, Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val hangUp = PendingIntent.getService(this, 41, Intent(this, VoiceCallService::class.java).setAction(ACTION_HANG_UP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_hermes)
            .setColor(0xFF0053FD.toInt())
            .setContentTitle(getString(R.string.call_ongoing, label))
            .setContentText(getString(R.string.call_ongoing_hint))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(open)
            .addAction(0, getString(R.string.call_hang_up), hangUp)
        if (connection.isNotBlank() && profile.isNotBlank()) {
            builder.setLargeIcon(com.nousresearch.hermesmobile.push.PushPeople.icon(this, connection, profile, 128))
        }
        return builder.build()
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }
}
