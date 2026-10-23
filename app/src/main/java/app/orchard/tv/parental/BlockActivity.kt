package app.orchard.tv.parental

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.orchard.tv.ui.parental.ParentalWallHost
import app.orchard.tv.ui.theme.LocalReducedMotion
import app.orchard.tv.ui.theme.canvasDensity

/**
 * The wall itself, over whatever is playing. [WatchService] opens it when a group's budget runs out
 * or when a locked app appears without the combo; it stays until the maths question, a parent's
 * password, or Back to Home ends it (docs/PARENTAL.md).
 */
class BlockActivity : ComponentActivity() {

    private var pkg = ""
    private var lockMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Parental.init(this)
        Usage.init(this)
        read(intent)
        if (pkg.isEmpty()) {
            finish()
            return
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.setBackgroundDrawable(null)
        // The math question must not be interrupted by the TV's or Aerial's screensaver.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val reduced = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        val label = runCatching {
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(pkg)

        setContent {
            val canvas = Density(canvasDensity(resources.displayMetrics), LocalDensity.current.fontScale)
            CompositionLocalProvider(LocalDensity provides canvas, LocalReducedMotion provides reduced) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    ParentalWallHost(
                        pkg = pkg,
                        appLabel = label,
                        lockMode = lockMode,
                        onAllowed = { finish() },
                        onCancel = { goHome() },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val before = pkg
        read(intent)
        setIntent(intent)
        // singleInstance reuses this instance, and the Compose content was built around the first
        // package: another app hitting the wall in the same second needs a fresh screen.
        if (pkg != before) recreate()
    }

    private fun read(intent: Intent?) {
        val next = intent?.getStringExtra(EXTRA_PACKAGE)
        if (!next.isNullOrBlank()) pkg = next
        lockMode = intent?.getBooleanExtra(EXTRA_LOCK, false) ?: false
    }

    private fun goHome() {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        finish()
    }

    override fun onStart() {
        super.onStart()
        isShowing = true
    }

    override fun onStop() {
        isShowing = false
        super.onStop()
    }

    companion object {
        private const val EXTRA_PACKAGE = "app.orchard.tv.parental.PACKAGE"
        private const val EXTRA_LOCK = "app.orchard.tv.parental.LOCK"

        /** True while the wall covers the screen: the time behind it does not count. */
        @Volatile
        var isShowing = false
            private set

        fun intent(context: Context, packageName: String, lock: Boolean): Intent =
            Intent(context, BlockActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                )
                .putExtra(EXTRA_PACKAGE, packageName)
                .putExtra(EXTRA_LOCK, lock)
    }
}
