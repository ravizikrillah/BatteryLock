package com.ravi.batterylock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

class BatteryMonitorService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastChargingSeen = false

    private fun wakeChargeLock() {
        runCatching {
            val wakeIntent = Intent("com.ravi.chargelock.WAKE_CHECK").apply {
                setPackage("com.ravi.chargelock")
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            sendBroadcast(wakeIntent)
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    handleBatteryCheck(batteryIntent = intent)
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    wakeChargeLock()
                    // Notify LockActivity immediately for seamless green morph & handoff to ChargeLock
                    sendBroadcast(Intent(BatteryLockState.ACTION_HANDOFF_TO_CHARGELOCK).setPackage(packageName))
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    // Check immediately and again shortly after ChargeLock finishes to Home
                    handleBatteryCheck(forceDisconnected = true)
                    mainHandler.postDelayed({ handleBatteryCheck() }, 200L)
                    mainHandler.postDelayed({ handleBatteryCheck() }, 550L)
                }
                Intent.ACTION_LOCALE_CHANGED -> {
                    startAsForeground()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startAsForeground()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_LOCALE_CHANGED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(batteryReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(batteryReceiver, filter)
        }

        handleBatteryCheck()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handleBatteryCheck()
        return START_STICKY
    }

    private fun handleBatteryCheck(
        batteryIntent: Intent? = null,
        forceDisconnected: Boolean = false
    ) {
        if (packageManager.isPackageSuspended) {
            stopSelf()
            return
        }
        val snapshot = BatteryLockState.evaluateAndUpdate(
            context = this,
            batteryIntent = batteryIntent,
            forceDisconnectedOverride = forceDisconnected
        )

        if (snapshot.isCharging && !lastChargingSeen) {
            wakeChargeLock()
        }
        lastChargingSeen = snapshot.isCharging

        if (snapshot.shouldLockScreen) {
            launchLockActivity()
        } else if (!snapshot.mustChargeUntilTarget) {
            sendBroadcast(Intent(BatteryLockState.ACTION_UNLOCK_REACHED_TARGET).setPackage(packageName))
        } else if (snapshot.isCharging) {
            sendBroadcast(Intent(BatteryLockState.ACTION_HANDOFF_TO_CHARGELOCK).setPackage(packageName))
        }
    }

    private fun launchLockActivity() {
        val lockIntent = Intent(this, LockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        startActivity(lockIntent)
    }

    private fun startAsForeground() {
        val channelId = "battery_lock_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_message))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(202, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(202, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(batteryReceiver) }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
