/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.services.keepalive

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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tom.rv2ide.R
import com.tom.rv2ide.resources.R.string
import org.slf4j.LoggerFactory

/**
 * Keeps the IDE process alive with a foreground service and (optionally) a floating overlay.
 *
 * The service follows the "keep alive" switches in `Settings > Developer options`. It is started by
 * [applyPreferences] and is intentionally tolerant: a missing notification permission or a denied
 * overlay permission never crashes the app, it only degrades the feature.
 *
 * @author AndroidCodeStudio
 */
class KeepAliveService : Service() {

  private val log = LoggerFactory.getLogger(KeepAliveService::class.java)

  private var floatingController: FloatingWindowController? = null

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
    startAsForeground()

    if (KeepAlivePreferences.floatingEnabled) {
      attachFloatingWindow()
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      stopSelf()
      return START_NOT_STICKY
    }

    if (!KeepAlivePreferences.enabled) {
      stopSelf()
      return START_NOT_STICKY
    }

    if (KeepAlivePreferences.floatingEnabled) {
      attachFloatingWindow()
    } else {
      detachFloatingWindow()
    }
    updateNotification()

    return START_STICKY
  }

  override fun onDestroy() {
    detachFloatingWindow()
    runCatching { stopForeground(true) }
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  // -----------------------------------------------------------------------------------------------
  // Notification
  // -----------------------------------------------------------------------------------------------

  private fun createNotificationChannel() {
    val channel =
        NotificationChannel(
            CHANNEL_ID,
            getString(string.keep_alive_channel_name),
            NotificationManager.IMPORTANCE_MIN,
        )
            .apply {
              description = getString(string.keep_alive_channel_description)
              setShowBadge(false)
            }
    NotificationManagerCompat.from(this).createNotificationChannel(channel)
  }

  private fun startAsForeground() {
    val notification = buildNotification()
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
      } else {
        startForeground(NOTIFICATION_ID, notification)
      }
    } catch (e: Exception) {
      // Missing POST_NOTIFICATIONS permission: keep the process alive without the notification.
      log.warn("Unable to start the keep alive service in the foreground", e)
    }
  }

  private fun updateNotification() {
    runCatching {
      val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
      manager.notify(NOTIFICATION_ID, buildNotification())
    }
  }

  private fun buildNotification(): Notification {
    val launch = packageManager.getLaunchIntentForPackage(packageName)
    val contentIntent =
        if (launch != null) {
          PendingIntent.getActivity(
              this,
              0,
              launch,
              PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
          )
        } else {
          null
        }

    val stopIntent =
        PendingIntent.getService(
            this,
            1,
            Intent(this, KeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    val status =
        getString(
            if (KeepAlivePreferences.floatingEnabled) {
              string.keep_alive_status_floating
            } else {
              string.keep_alive_status_running
            }
        )

    val builder =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_notification)
            .setContentTitle(getString(string.keep_alive_notification_title))
            .setContentText(status)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .addAction(0, getString(string.keep_alive_action_stop), stopIntent)

    contentIntent?.let { builder.setContentIntent(it) }
    return builder.build()
  }

  // -----------------------------------------------------------------------------------------------
  // Floating window
  // -----------------------------------------------------------------------------------------------

  private fun attachFloatingWindow() {
    if (floatingController?.isAttached == true) {
      floatingController?.refreshAppearance()
      return
    }
    val controller = FloatingWindowController(this)
    if (controller.attach()) {
      floatingController = controller
    } else {
      log.warn("Floating window could not be attached (permission missing?)")
    }
  }

  private fun detachFloatingWindow() {
    floatingController?.detach()
    floatingController = null
  }

  companion object {

    const val CHANNEL_ID = "ide_keepalive"
    private const val NOTIFICATION_ID = 0x4B454550 // "KEEP"

    const val ACTION_STOP = "com.tom.rv2ide.action.STOP_KEEP_ALIVE"

    /** Starts or stops the service according to the current preferences. */
    @JvmStatic
    fun applyPreferences(context: Context) {
      if (!KeepAlivePreferences.enabled) {
        stop(context)
        return
      }

      val intent = Intent(context, KeepAliveService::class.java)
      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          context.startForegroundService(intent)
        } else {
          context.startService(intent)
        }
      } catch (e: Exception) {
        org.slf4j.LoggerFactory.getLogger(KeepAliveService::class.java)
            .warn("Unable to start the keep alive service", e)
      }
    }

    /** Stops the service. */
    @JvmStatic
    fun stop(context: Context) {
      runCatching { context.stopService(Intent(context, KeepAliveService::class.java)) }
    }

    /** `true` when the service is expected to be running. */
    @JvmStatic
    fun isEnabled(): Boolean = KeepAlivePreferences.enabled
  }
}
