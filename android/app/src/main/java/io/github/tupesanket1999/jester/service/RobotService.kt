package io.github.tupesanket1999.jester.service

import io.github.tupesanket1999.jester.JesterApp
import io.github.tupesanket1999.jester.bot.BotRepo
import io.github.tupesanket1999.jester.notify.Notifier
import io.github.tupesanket1999.jester.notify.launchCatching
import io.github.tupesanket1999.jester.overlay.Overlay
import io.github.tupesanket1999.jester.push.NtfyStream
import io.github.tupesanket1999.jester.reminders.Alarms
import io.github.tupesanket1999.jester.ui.SheetActivity
import io.github.tupesanket1999.jester.update.Updater
import io.github.tupesanket1999.jester.ui.VoiceActivity
import android.content.IntentFilter
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The always-on part: foreground service (type specialUse) with a silent MIN notification. Owns the overlay robot,
 * the ntfy push stream, the write-queue drain loop and the periodic hub refresh (tasks -> local alarms, ringing, status).
 */
class RobotService : Service() {
    private lateinit var app: JesterApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())
    private var overlay: Overlay? = null
    private lateinit var ntfy: NtfyStream

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        app = JesterApp.get(this)
        startInForeground()
        ntfy = NtfyStream(app)
        app.reportTimezone() // hub learns the phone's timezone on every start (and on change, see SystemReceiver)

        overlay = Overlay(this, app.prefs, BotRepo.load(this),
            onTap = { SheetActivity.open(this, SheetActivity.MODE_CHAT) },
            onLongPress = { VoiceActivity.open(this) }, // long-press = talk to Jester; the menu is in the chat sheet ("More")
        ).also { it.attach() }
        applyHidden()

        scope.launch { app.ringing.collectLatest { overlay?.showRinging(it) } }
        scope.launch { app.robotQuip.collectLatest { overlay?.showQuip(it) } }
        registerBattery()
        scope.launch { app.botVersion.collectLatest { if (it > 0) overlay?.setPack(BotRepo.load(this@RobotService)) } }
        scope.launch(Dispatchers.IO) { ntfy.run() }
        scope.launch(Dispatchers.IO) { syncLoop() }
        Updater.autoCheck(app) // at most once a day, silent on failure
    }

    private val QUIPS = listOf(
        "Battery low. Feed me or I forget your stuff.",
        "Running on fumes. Charger, please. I am too young to die.",
        "Low battery. I will remember your reminders in my dreams.",
    )
    private val battery = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_BATTERY_LOW -> if (app.prefs.trigger("battery")) {
                    app.robotQuip.value = QUIPS.random()
                    main.postDelayed({ app.robotQuip.value = null }, 12_000)
                }
                Intent.ACTION_BATTERY_OKAY -> app.robotQuip.value = null
            }
        }
    }

    private fun registerBattery() {
        val f = IntentFilter().apply { addAction(Intent.ACTION_BATTERY_LOW); addAction(Intent.ACTION_BATTERY_OKAY) }
        // system broadcasts: not exported to other apps
        ContextCompat.registerReceiver(this, battery, f, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun startInForeground() {
        val n = Notifier.serviceNotification(this, robotShown())
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notifier.SERVICE_ID, n, type)
    }

    private fun robotShown() = !app.prefs.robotHidden && System.currentTimeMillis() >= app.prefs.hiddenUntil

    private fun applyHidden() {
        overlay?.setShown(robotShown())
        main.removeCallbacksAndMessages(HIDE_TOKEN)
        val until = app.prefs.hiddenUntil
        if (until > System.currentTimeMillis()) main.postAtTime({ applyHidden(); refreshNotification() }, HIDE_TOKEN,
            android.os.SystemClock.uptimeMillis() + (until - System.currentTimeMillis()))
    }

    private fun refreshNotification() = startInForeground()

    /** Queue drain every 30 s (sooner when kicked); ringing every minute; status + tasks/alarms every 15 min. */
    private suspend fun syncLoop() {
        var tick = 0
        while (scope.isActive) {
            runCatching { app.drainNow() }
            if (tick % 2 == 0) runCatching { app.refreshRinging() }
            if (tick % 30 == 0) runCatching {
                app.refreshStatus()
                if (!app.refreshTasks()) Alarms.reschedule(this, app.tasks.value) // offline: re-plan from cache
            }
            tick++
            delay(30_000)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> { app.prefs.robotHidden = true; applyHidden(); refreshNotification() }
            ACTION_SHOW -> { app.prefs.robotHidden = false; app.prefs.hiddenUntil = 0; overlay?.attach(); applyHidden(); refreshNotification() }
            ACTION_HIDE_HOUR -> { app.prefs.hiddenUntil = System.currentTimeMillis() + 3600_000L; applyHidden(); refreshNotification() }
            ACTION_SYNC -> app.scope.launchCatching({}) { app.refreshStatus(); app.refreshRinging(); app.refreshTasks(); app.drainNow() }
            ACTION_APPLY_FX -> overlay?.applySettings()
            ACTION_SETTINGS_CHANGED -> { ntfy.stop(); overlay?.attach(); applyHidden(); Updater.autoCheck(app) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(battery) }
        ntfy.stop()
        scope.cancel()
        main.removeCallbacksAndMessages(null)
        overlay?.detach()
        overlay?.release()
        super.onDestroy()
    }

    companion object {
        const val ACTION_HIDE = "io.github.tupesanket1999.jester.HIDE"
        const val ACTION_SHOW = "io.github.tupesanket1999.jester.SHOW"
        const val ACTION_HIDE_HOUR = "io.github.tupesanket1999.jester.HIDE_HOUR"
        const val ACTION_SYNC = "io.github.tupesanket1999.jester.SYNC"
        const val ACTION_APPLY_FX = "io.github.tupesanket1999.jester.APPLY_FX"
        const val ACTION_SETTINGS_CHANGED = "io.github.tupesanket1999.jester.SETTINGS_CHANGED"
        private val HIDE_TOKEN = Any()

        fun start(ctx: Context, action: String? = null) {
            val i = Intent(ctx, RobotService::class.java).setAction(action)
            runCatching { ContextCompat.startForegroundService(ctx, i) }
        }
    }
}

/** Boot / app update: restart the service and re-arm alarms. Timezone change: re-arm alarms and tell the hub. */
class SystemReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = JesterApp.get(ctx)
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Alarms.reschedule(ctx, app.loadCachedTasks())
                if (app.prefs.setupDone) RobotService.start(ctx)
            }
            Intent.ACTION_TIMEZONE_CHANGED -> {
                Alarms.reschedule(ctx, app.loadCachedTasks()) // floating local times follow the new zone
                val pending = goAsync()
                app.scope.launchCatching({ pending.finish() }) { app.reportTimezone(); app.drainNow() }
            }
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" -> Alarms.reschedule(ctx, app.loadCachedTasks())
        }
    }
}
