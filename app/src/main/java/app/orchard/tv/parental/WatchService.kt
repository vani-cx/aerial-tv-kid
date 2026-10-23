package app.orchard.tv.parental

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.getSystemService

/**
 * Watches the front window and keeps the time budgets (docs/PARENTAL.md).
 *
 * Every five seconds it adds the time since the last look to the group in front, and only while
 * `PowerManager.isInteractive` says the screen is on: standby and a black panel cost nothing. A gap
 * longer than [MAX_GAP_MS] is a standby the TV slept through, so it counts as nothing either.
 *
 * The service reads window changes only (`canRetrieveWindowContent` is off, see res/xml), which is
 * what an accessibility service needs to answer "which app is in front". Android starts it at boot
 * and keeps it alive, which also lets it open [BlockActivity] over whatever is playing, the way
 * tvwall does.
 */
class WatchService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastTick = 0L
    private var foreground: String? = null
    private var blockedAt = 0L

    override fun onServiceConnected() {
        instance = this
        running = true
        Parental.init(this)
        Usage.init(this)
        Parental.closeAdmin()
        lastTick = System.currentTimeMillis()
        handler.postDelayed(tick, TICK_MS)
        Log.i(TAG, "watching the TV")
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (instance === this) {
            instance = null
            running = false
        }
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return
        // Dialogs and overlays raise this event too; only a real activity is "what is in front".
        if (!isActivity(pkg, cls)) return
        val now = System.currentTimeMillis()
        if (pkg == packageName) {
            // The wall is drawn over an app, not over Home: the app behind it stays "in front", so
            // the rules look at it again the moment the wall goes away.
            if (cls.contains("BlockActivity")) return
            onHome(now)
            return
        }
        onForeground(pkg, now)
    }

    private fun onHome(now: Long) {
        val from = foreground ?: return
        foreground = null
        Parental.onForegroundChanged(from, null)
    }

    private fun onForeground(pkg: String, now: Long) {
        val from = foreground
        if (from == pkg) return
        foreground = pkg
        Parental.onForegroundChanged(from, pkg)
        Parental.groupOf(pkg)?.let { Usage.beginSession(it.id, now) }
        enforce(pkg, now)
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val delta = now - lastTick
            lastTick = now
            count(foreground, delta, now)
            foreground?.let { enforce(it, now) }
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** Adds [deltaMs] of screen-on time in front of [pkg], if it belongs to a group. */
    private fun count(pkg: String?, deltaMs: Long, now: Long) {
        if (pkg == null || BlockActivity.isShowing) return
        val group = Parental.groupOf(pkg) ?: return
        if (deltaMs <= 0 || deltaMs > MAX_GAP_MS) return
        if (getSystemService<PowerManager>()?.isInteractive != true) return
        Usage.addUsed(group.id, (deltaMs / 1000).toInt(), now)
        Parental.touchUsage()
    }

    private fun enforce(pkg: String, now: Long) {
        if (BlockActivity.isShowing) return
        val verdict = Parental.verdict(pkg, now)
        if (verdict is Verdict.Allow) return
        if (now - blockedAt < REOPEN_MS) return
        blockedAt = now
        runCatching { startActivity(BlockActivity.intent(this, pkg, verdict is Verdict.Locked)) }
            .onFailure { Log.w(TAG, "could not show the block screen", it) }
    }

    private val activities = HashMap<Pair<String, String>, Boolean>()

    private fun isActivity(pkg: String, cls: String): Boolean = activities.getOrPut(pkg to cls) {
        runCatching { packageManager.getActivityInfo(android.content.ComponentName(pkg, cls), 0) }.isSuccess
    }

    companion object {
        private const val TAG = "AerialKid"
        private const val TICK_MS = 5_000L

        /** A longer gap between looks means the TV was in standby, so none of it counts. */
        private const val MAX_GAP_MS = 15_000L

        /** Do not reopen the block screen more often than this while it is being dismissed. */
        private const val REOPEN_MS = 1_500L

        @Volatile
        private var instance: WatchService? = null

        /** Whether the viewer has switched the service on; shown in the settings sheet. */
        var running by mutableStateOf(false); private set

        /**
         * Whether the owner has switched the service on in Settings, bound or not: the status row
         * reads this so "not running" only shows when it is really off, not merely unbound.
         */
        fun enabledInSettings(context: Context): Boolean {
            val list = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            val mine = context.packageName + "/" + WatchService::class.java.name
            return list.split(':').any { it.trim().equals(mine, ignoreCase = true) }
        }

        /**
         * Called by Aerial itself, which is the one place that knows a group's app was just
         * opened with the launcher in front: a session starts even before the service sees a window.
         */
        fun onAppOpened(pkg: String) {
            val group = Parental.groupOf(pkg) ?: return
            Usage.beginSession(group.id)
        }
    }
}

/**
 * The same five-second count for TVs where the accessibility service is not switched on: Aerial
 * starts it when it opens one of a group's apps and stops it when Home comes back. It only covers
 * "opened from the launcher and left again"; with the service on, that one is authoritative.
 */
object FallbackClock {
    private val handler = Handler(Looper.getMainLooper())
    private var pkg: String? = null
    private var lastTick = 0L
    private var blockedAt = 0L

    fun start(context: Context, packageName: String) {
        if (WatchService.running) return
        val group = Parental.groupOf(packageName) ?: return
        Usage.beginSession(group.id)
        pkg = packageName
        lastTick = System.currentTimeMillis()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, TICK_MS)
    }

    fun stop() {
        val current = pkg ?: return
        pkg = null
        handler.removeCallbacks(tick)
        Parental.groupOf(current)?.let { Usage.endSession(it.id) }
        Parental.lockApp(current)
        Parental.touchUsage()
    }

    val isCounting: Boolean get() = pkg != null

    private val tick = object : Runnable {
        override fun run() {
            val context = instance ?: return
            val watching = pkg
            if (watching == null) return
            if (WatchService.running) {
                stop()
                return
            }
            val now = System.currentTimeMillis()
            val delta = now - lastTick
            lastTick = now
            val group = Parental.groupOf(watching)
            val on = context.getSystemService<PowerManager>()?.isInteractive == true
            if (group != null && on && !BlockActivity.isShowing && delta in 1..MAX_GAP_MS) {
                Usage.addUsed(group.id, (delta / 1000).toInt(), now)
                Parental.touchUsage()
            }
            if (!BlockActivity.isShowing) {
                val verdict = Parental.verdict(watching, now)
                if (verdict !is Verdict.Allow && now - blockedAt > 1_500L) {
                    blockedAt = now
                    runCatching {
                        context.startActivity(BlockActivity.intent(context, watching, verdict is Verdict.Locked))
                    }
                }
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private const val TICK_MS = 5_000L
    private const val MAX_GAP_MS = 15_000L

    /** Aerial's own context, kept so the timer can start activities and read the screen state. */
    @Volatile
    var instance: Context? = null
}
