package app.orchard.tv.system

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo

/**
 * Some AOSP TV Settings builds show MainSettings as a translucent right-hand panel. Starting it
 * in a separate task leaves that task's black background behind the panel (reported on a CM311-1
 * running a modified Android 9 ATV firmware). An Activity can keep it above its own content.
 *
 * Do not change full-screen/OEM settings, application-context launches, or normal app launches.
 * This only removes a caller-supplied NEW_TASK; it cannot override a target's manifest launchMode.
 */
internal fun settingsLaunchIntent(context: Context, intent: Intent): Intent {
    var caller = context
    while (caller !is Activity && caller is ContextWrapper) {
        val base = caller.baseContext
        if (base === caller) return intent
        caller = base
    }
    if (caller !is Activity) return intent
    val info = runCatching { context.packageManager.resolveActivity(intent, 0)?.activityInfo }
        .getOrNull() ?: return intent
    if (info.packageName != "com.android.tv.settings" ||
        info.name != "com.android.tv.settings.MainSettings" ||
        info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0
    ) return intent
    return Intent(intent).apply { flags = flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv() }
}
