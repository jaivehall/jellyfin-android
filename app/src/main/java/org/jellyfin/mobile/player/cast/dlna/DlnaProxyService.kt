package org.jellyfin.mobile.player.cast.dlna

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
import org.jellyfin.mobile.MainActivity
import org.jellyfin.mobile.R
import org.jellyfin.mobile.utils.AndroidVersion
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.createMediaNotificationChannel
import timber.log.Timber

/**
 * Foreground service that keeps the app process alive while a DLNA cast is
 * relayed through [DlnaStreamProxy].
 *
 * A plain background thread would be frozen or killed once the screen locks,
 * which would tear down the proxy socket and make the TV stall on "receiving
 * data". Running the relay under a foreground service (plus a partial wake
 * lock) keeps the process at foreground priority and the CPU awake, so the TV
 * can keep pulling the stream even with the phone locked and the app in the
 * background. No changes to the Jellyfin server are required.
 */
class DlnaProxyService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        acquireWakeLock()
        return START_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm != null) createMediaNotificationChannel(nm)

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0,
        )
        val notification = NotificationCompat.Builder(this, Constants.MEDIA_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.dlna_proxy_notification_title))
            .setContentText(getString(R.string.dlna_proxy_notification_text))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp)
            .build()

        val type = if (AndroidVersion.isAtLeastQ) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                ?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }.onFailure { Timber.w(it, "DLNA proxy: could not acquire wake lock") }
    }

    override fun onDestroy() {
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
        }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 100
        private const val WAKE_LOCK_TAG = "finplayer:dlna-proxy"

        fun start(context: Context) {
            val intent = Intent(context, DlnaProxyService::class.java)
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }.onFailure { Timber.w(it, "Not allowed to start DLNA proxy foreground service") }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DlnaProxyService::class.java))
        }
    }
}
