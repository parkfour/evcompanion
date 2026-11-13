package com.evcompanion.core.polling

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.evcompanion.App
import com.evcompanion.core.debug.DebugFileLogger
import com.evcompanion.phone.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the process (and the Bluetooth connection [PollingEngine]
 * owns) alive while connected to an OBD adapter. All actual connect/poll/decode logic lives in
 * [PollingEngine] - this class only owns the required foreground notification and forwards
 * connect/disconnect calls to it.
 */
class ObdPollingService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS) ?: return START_NOT_STICKY
                try {
                    startForegroundWithNotification()
                } catch (e: SecurityException) {
                    DebugFileLogger.e(TAG, "SecurityException starting foreground service: ${e.message}")
                    stopSelf()
                    return START_NOT_STICKY
                }
                (application as App).container.pollingEngine.connectAsync(address)
            }
            ACTION_DISCONNECT -> {
                serviceScope.launch {
                    runCatching { (application as App).container.pollingEngine.disconnect() }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun startForegroundWithNotification() {
        // minSdk is 26 (O), so notification channels always exist - no version check needed.
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "OBD connection", NotificationManager.IMPORTANCE_LOW)
        )

        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("EV Companion")
            .setContentText("Connected to OBD adapter")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "ObdPollingService"
        private const val CHANNEL_ID = "obd_connection"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_CONNECT = "com.evcompanion.action.CONNECT"
        private const val ACTION_DISCONNECT = "com.evcompanion.action.DISCONNECT"
        private const val EXTRA_DEVICE_ADDRESS = "device_address"

        fun hasBluetoothPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        }

        fun start(context: Context, deviceAddress: String) {
            if (!hasBluetoothPermission(context)) {
                DebugFileLogger.w(TAG, "Cannot start ObdPollingService: BLUETOOTH_CONNECT permission not granted yet")
                return
            }
            val intent = Intent(context, ObdPollingService::class.java)
                .setAction(ACTION_CONNECT)
                .putExtra(EXTRA_DEVICE_ADDRESS, deviceAddress)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ObdPollingService::class.java).setAction(ACTION_DISCONNECT)
            context.startService(intent)
        }
    }
}
