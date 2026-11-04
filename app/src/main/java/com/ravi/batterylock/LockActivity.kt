package com.ravi.batterylock

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ravi.batterylock.ui.theme.BatteryLockTheme

class LockActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val batteryLevel = mutableIntStateOf(20)
    private val isChargingHandoff = mutableStateOf(false)
    private var handoffScheduled = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val snap = BatteryLockState.evaluateAndUpdate(this@LockActivity, intent)
                    batteryLevel.intValue = snap.level
                    when {
                        !snap.mustChargeUntilTarget && !snap.isCharging -> unlockToHome()
                        snap.isCharging -> triggerSeamlessChargeLockHandoff()
                        else -> cancelHandoffAndHoldLock()
                    }
                }
                Intent.ACTION_POWER_CONNECTED,
                BatteryLockState.ACTION_HANDOFF_TO_CHARGELOCK -> {
                    triggerSeamlessChargeLockHandoff()
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    val snap = BatteryLockState.evaluateAndUpdate(
                        this@LockActivity,
                        forceDisconnectedOverride = true
                    )
                    batteryLevel.intValue = snap.level
                    if (snap.shouldLockScreen) {
                        cancelHandoffAndHoldLock()
                    } else {
                        unlockToHome()
                    }
                }
                BatteryLockState.ACTION_UNLOCK_REACHED_TARGET -> {
                    val snap = BatteryLockState.evaluateAndUpdate(this@LockActivity)
                    if (!snap.mustChargeUntilTarget && !snap.isCharging) {
                        unlockToHome()
                    }
                }
            }
        }
    }

    private fun triggerSeamlessChargeLockHandoff() {
        isChargingHandoff.value = true
        if (handoffScheduled) return
        handoffScheduled = true

        // Send direct handoff signal to ChargeLock while morphing this screen to identical Green UI
        runCatching {
            sendBroadcast(
                Intent("com.ravi.chargelock.OPEN_LOCK")
                    .setPackage("com.ravi.chargelock")
            )
            sendBroadcast(
                Intent("com.ravi.chargelockv2.OPEN_LOCK")
                    .setPackage("com.ravi.chargelockv2")
            )
        }

        handler.postDelayed({
            val snap = BatteryLockState.evaluateAndUpdate(this)
            if (snap.isCharging) {
                finish()
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            } else {
                cancelHandoffAndHoldLock()
            }
        }, 650L)
    }

    private fun cancelHandoffAndHoldLock() {
        handoffScheduled = false
        isChargingHandoff.value = false
        handler.removeCallbacksAndMessages(null)
    }

    private fun unlockToHome() {
        handoffScheduled = false
        handler.removeCallbacksAndMessages(null)
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finishAffinity()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)

        val initialSnap = BatteryLockState.evaluateAndUpdate(this)
        batteryLevel.intValue = initialSnap.level

        if (!initialSnap.shouldLockScreen) {
            if (initialSnap.isCharging) {
                finish()
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            } else {
                unlockToHome()
            }
            return
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(BatteryLockState.ACTION_HANDOFF_TO_CHARGELOCK)
            addAction(BatteryLockState.ACTION_UNLOCK_REACHED_TARGET)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(stateReceiver, filter)
        }

        enableEdgeToEdge()
        setContent {
            BatteryLockTheme {
                BackHandler {
                    // Block back button completely
                }
                SeamlessBatteryLockScreen(
                    batteryLevel = batteryLevel.intValue,
                    isChargingHandoff = isChargingHandoff.value
                )
            }
        }

        hideSystemUI()
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun shouldEnforceLockRightNow(): Boolean {
        if (isChargingHandoff.value || isFinishing) return false
        return BatteryLockState.evaluateAndUpdate(this).shouldLockScreen
    }

    private fun bringBackToFront() {
        val intent = Intent(this, LockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        startActivity(intent)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        } else if (shouldEnforceLockRightNow()) {
            bringBackToFront()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (shouldEnforceLockRightNow()) {
            bringBackToFront()
        }
    }

    @Suppress("DEPRECATION")
    override fun onPause() {
        super.onPause()
        if (shouldEnforceLockRightNow()) {
            window.decorView.postDelayed({
                if (shouldEnforceLockRightNow()) {
                    val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    am.moveTaskToFront(taskId, 0)
                    bringBackToFront()
                }
            }, 90L)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(stateReceiver) }
    }
}

@Composable
fun SeamlessBatteryLockScreen(
    batteryLevel: Int,
    isChargingHandoff: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "battery_lock_transition")

    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "icon_pulse"
    )

    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring_sweep"
    )

    // Smooth color morph between Low-Battery Red/Amber and ChargeLockV2 Green/Lime
    val ringPrimary by animateColorAsState(
        targetValue = if (isChargingHandoff) Color(0xFF00C853) else Color(0xFFFF1744),
        animationSpec = tween(280),
        label = "ring_primary"
    )
    val ringSecondary by animateColorAsState(
        targetValue = if (isChargingHandoff) Color(0xFFB2FF59) else Color(0xFFFF9100),
        animationSpec = tween(280),
        label = "ring_secondary"
    )
    val iconTint by animateColorAsState(
        targetValue = if (isChargingHandoff) Color.Yellow else Color(0xFFFF5252),
        animationSpec = tween(280),
        label = "icon_tint"
    )
    val badgeColor by animateColorAsState(
        targetValue = if (isChargingHandoff) Color(0xFFB2FF59) else Color(0xFFFF6E40),
        animationSpec = tween(280),
        label = "badge_color"
    )

    val headerText = when {
        isChargingHandoff -> stringResource(id = R.string.charging_handoff_title)
        batteryLevel <= BatteryLockState.LOW_THRESHOLD -> stringResource(id = R.string.low_battery_title)
        else -> stringResource(id = R.string.under_target_title, BatteryLockState.TARGET_THRESHOLD)
    }

    val messageText = when {
        isChargingHandoff -> stringResource(id = R.string.charging_handoff_message)
        batteryLevel <= BatteryLockState.LOW_THRESHOLD ->
            stringResource(
                id = R.string.low_battery_message,
                batteryLevel,
                BatteryLockState.TARGET_THRESHOLD
            )
        else ->
            stringResource(
                id = R.string.under_target_message,
                batteryLevel,
                BatteryLockState.TARGET_THRESHOLD
            )
    }

    // Exact same geometry, sizes (220.dp, 60.dp, 54.sp, 48.dp spacer, 20.dp card padding)
    // and #0F0F0F background as ChargeLockV2 for 100% seamless visual handoff
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0F0F0F)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(220.dp)) {
                    drawArc(
                        color = Color.DarkGray,
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
                    )
                    drawArc(
                        brush = Brush.sweepGradient(
                            listOf(ringPrimary, ringSecondary, ringPrimary)
                        ),
                        startAngle = sweepAngle,
                        sweepAngle = 90f,
                        useCenter = false,
                        style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = if (isChargingHandoff) Icons.Default.FlashOn else Icons.Default.BatteryAlert,
                        contentDescription = "Battery Status",
                        tint = iconTint.copy(alpha = alpha),
                        modifier = Modifier.size(60.dp)
                    )
                    Text(
                        text = "$batteryLevel%",
                        color = Color.White,
                        fontSize = 54.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            Surface(
                shape = MaterialTheme.shapes.large,
                color = Color.White.copy(alpha = 0.1f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = headerText,
                        color = badgeColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = messageText,
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        lineHeight = 28.sp
                    )
                }
            }
        }
    }
}
