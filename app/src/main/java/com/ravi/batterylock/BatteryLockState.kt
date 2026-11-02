package com.ravi.batterylock

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

data class BatterySnapshot(
    val level: Int,
    val isCharging: Boolean,
    val mustChargeUntilTarget: Boolean
) {
    val shouldLockScreen: Boolean
        get() = mustChargeUntilTarget && !isCharging
}

object BatteryLockState {
    const val LOW_THRESHOLD = 20
    const val TARGET_THRESHOLD = 50

    private const val PREFS_NAME = "battery_lock_prefs"
    private const val KEY_MUST_CHARGE_TO_TARGET = "must_charge_to_target"

    const val ACTION_HANDOFF_TO_CHARGELOCK = "com.ravi.batterylock.HANDOFF_TO_CHARGELOCK"
    const val ACTION_UNLOCK_REACHED_TARGET = "com.ravi.batterylock.UNLOCK_REACHED_TARGET"

    private fun prefs(context: Context) =
        context.applicationContext
            .createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun evaluateAndUpdate(
        context: Context,
        batteryIntent: Intent? = null,
        forceDisconnectedOverride: Boolean = false
    ): BatterySnapshot {
        val stickyIntent = batteryIntent ?: context.applicationContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        val rawLevel = stickyIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = stickyIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val level = if (rawLevel >= 0 && scale > 0) {
            ((rawLevel * 100f) / scale).toInt().coerceIn(0, 100)
        } else {
            100
        }

        val status = stickyIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = stickyIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val isCharging = if (forceDisconnectedOverride) {
            false
        } else {
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL ||
                plugged != 0
        }

        val sp = prefs(context)
        val previousMustCharge = sp.getBoolean(KEY_MUST_CHARGE_TO_TARGET, false)
        val updatedMustCharge = when {
            level >= TARGET_THRESHOLD -> false
            level <= LOW_THRESHOLD -> true
            else -> previousMustCharge
        }

        if (updatedMustCharge != previousMustCharge) {
            sp.edit().putBoolean(KEY_MUST_CHARGE_TO_TARGET, updatedMustCharge).apply()
        }

        return BatterySnapshot(
            level = level,
            isCharging = isCharging,
            mustChargeUntilTarget = updatedMustCharge
        )
    }
}
