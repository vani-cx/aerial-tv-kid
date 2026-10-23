package app.orchard.tv.parental

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** A D-pad direction: the alphabet of the children's combo password. */
enum class Dir { Up, Down, Left, Right }

/** Minutes from midnight, [start] inclusive, [end] exclusive. */
data class Window(val start: Int, val end: Int) {
    fun has(minute: Int): Boolean = minute >= start && minute < end
}

/** How one group may be used on one weekday. Zero minutes means "no limit" for that dimension. */
data class DayRule(
    val enabled: Boolean = true,
    val dailyMinutes: Int = 60,
    val sessionMinutes: Int = 30,
    val breakMinutes: Int = 10,
    /** Allowed stretches of the day; empty means all day. */
    val windows: List<Window> = emptyList(),
)

/** A set of apps that share one time budget. */
data class Group(
    val id: String,
    val name: String,
    val packages: Set<String> = emptySet(),
    /** Monday first, seven entries. */
    val days: List<DayRule> = List(7) { DayRule() },
    val enabled: Boolean = true,
) {
    fun dayRule(now: Long): DayRule = days.getOrElse(weekdayIndex(now)) { DayRule() }
}

enum class Reason { Daily, Session, Break, Window }

/**
 * What happens when someone tries to open [pkg], or keeps using it.
 */
sealed interface Verdict {
    data object Allow : Verdict

    /** The app is locked: the remote combo (or a parent's password) opens it. */
    data object Locked : Verdict

    data class Blocked(
        val group: Group,
        val reason: Reason,
        /** Seconds and minutes the group has used today, and its allowance, for the message. */
        val usedSec: Int,
        val limitMinutes: Int,
        /** Minutes until it opens again by itself; 0 when only a parent can help. */
        val waitMinutes: Int,
    ) : Verdict
}

/** Monday = 0 … Sunday = 6, in the TV's own time zone. */
fun weekdayIndex(now: Long): Int {
    val c = Calendar.getInstance().apply { timeInMillis = now }
    return (c.get(Calendar.DAY_OF_WEEK) + 5) % 7
}

fun minuteOfDay(now: Long): Int {
    val c = Calendar.getInstance().apply { timeInMillis = now }
    return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
}

fun dayKey(now: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = now }
    return "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
}

/** 570 -> "9:30", 1440 -> "24:00". */
fun clock(minute: Int): String = "%d:%02d".format(minute / 60, minute % 60)

/**
 * Parental controls: two passwords, locked apps and time budgets (see docs/PARENTAL.md).
 *
 * Two levels, as the owner asked:
 *  - the **admin password**: eight characters, letters and digits mixed, opens Settings, changing
 *    either password, and buying extra minutes. It also replaces Aerial's old four-digit PIN
 *    (AerialSettings) once it is set, see HomeViewModel.whenUnlocked.
 *  - the **combo**: a sequence of D-pad directions, confirmed with OK, that opens a locked app. It
 *    is deliberately weak (a few thousand combinations at most) and only ever guards "can this app
 *    start"; every loosening of a rule goes through the admin password.
 *
 * State only, on the main thread; hashing runs off it (Secrets). Preferences: "parental".
 */
object Parental {
    private const val PREFS = "parental"
    private const val KEY_ADMIN = "admin_hash"
    private const val KEY_COMBO = "combo_hash"
    private const val KEY_COMBO_LEN = "combo_len"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LOCKED = "locked"
    private const val KEY_GROUPS = "groups"
    private const val KEY_FAILS = "fails"
    private const val KEY_LOCKOUTS = "lockouts"
    private const val KEY_BLOCKED_UNTIL = "blocked_until"

    /** How long one admin entry keeps Settings open, so a parent is not asked at every row. */
    private const val ADMIN_WINDOW_MS = 10 * 60 * 1000L

    /** Wrong entries allowed before waiting: the combo has too few combinations to allow guessing. */
    private const val MAX_FAILS = 5

    /**
     * The first wait after [MAX_FAILS] wrong entries, doubled for every further round: 30 s, 1 min,
     * 2 min, 4 min, then 8 min. A four-direction combo is only a few hundred sequences, so a flat
     * half-minute wait would let a child work through them in a quarter of an hour.
     */
    private const val LOCKOUT_MS = 30_000L
    private const val MAX_DOUBLINGS = 4

    /** Quiet time after a wait that starts the doubling over. */
    private const val QUIET_MS = 60 * 60 * 1000L

    private var started = false
    private lateinit var app: Context

    var enabled by mutableStateOf(false); private set
    var adminSet by mutableStateOf(false); private set
    var comboSet by mutableStateOf(false); private set
    var comboLength by mutableStateOf(6); private set
    var groups by mutableStateOf<List<Group>>(emptyList()); private set
    var lockedPackages by mutableStateOf<Set<String>>(emptySet()); private set

    /** Bumped whenever today's usage changes, so the settings screen redraws its numbers. */
    var revision by mutableStateOf(0); private set

    private var adminHash: String? = null
    private var comboHash: String? = null
    private var adminAt = 0L
    private var fails = 0

    /** Rounds of [MAX_FAILS] wrong entries so far today, each doubling the wait before the next try. */
    private var lockouts = 0
    private var blockedUntil = 0L

    /** Apps opened with the combo this session; dropped when the viewer leaves them. */
    private val unlocked = HashSet<String>()

    fun init(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        val p = prefs()
        adminHash = p.getString(KEY_ADMIN, null)
        comboHash = p.getString(KEY_COMBO, null)
        adminSet = adminHash != null
        comboSet = comboHash != null
        comboLength = p.getInt(KEY_COMBO_LEN, 6).coerceIn(MIN_COMBO, MAX_COMBO)
        enabled = p.getBoolean(KEY_ENABLED, false)
        lockedPackages = p.getStringSet(KEY_LOCKED, null)?.toSet().orEmpty()
        groups = runCatching { parseGroups(p.getString(KEY_GROUPS, null)) }.getOrDefault(emptyList())
        fails = p.getInt(KEY_FAILS, 0)
        lockouts = p.getInt(KEY_LOCKOUTS, 0)
        blockedUntil = p.getLong(KEY_BLOCKED_UNTIL, 0L)
    }

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val configured: Boolean get() = adminSet

    // ---- Passwords ----

    /** True while one right admin password still keeps Settings open. */
    val adminOpen: Boolean get() = System.currentTimeMillis() < adminAt

    fun noteAdminEntry() {
        adminAt = System.currentTimeMillis() + ADMIN_WINDOW_MS
    }

    fun closeAdmin() { adminAt = 0L }

    /** Milliseconds still to wait after too many wrong entries; 0 when entries are allowed. */
    fun waitMs(): Long = (blockedUntil - System.currentTimeMillis()).coerceAtLeast(0L)

    /**
     * Checks the admin password. Runs PBKDF2: call it off the main thread.
     * Eight characters mixing letters and digits, as the owner asked.
     */
    fun verifyAdmin(password: String): Boolean {
        if (waitMs() > 0) return false
        val ok = Secrets.verify(password, adminHash)
        count(ok)
        if (ok) noteAdminEntry()
        return ok
    }

    /** Checks a direction combo. Runs a hash of the whole sequence: cheap, call off the main thread. */
    fun verifyCombo(dirs: List<Dir>): Boolean {
        if (waitMs() > 0) return false
        if (dirs.size != comboLength) return false
        val ok = Secrets.verify(dirs.joinToString("-") { it.name }, comboHash)
        count(ok)
        return ok
    }

    private fun count(ok: Boolean) {
        val now = System.currentTimeMillis()
        if (ok) {
            fails = 0
            lockouts = 0
            blockedUntil = 0L
        } else {
            // A quiet hour after the last wait starts the count over: the wait doubles within one
            // sitting, not across weeks of a child trying once a day.
            if (now - blockedUntil > QUIET_MS) lockouts = 0
            fails++
            if (fails >= MAX_FAILS) {
                fails = 0
                blockedUntil = now + (LOCKOUT_MS shl lockouts.coerceAtMost(MAX_DOUBLINGS))
                lockouts++
            }
        }
        prefs().edit { putInt(KEY_FAILS, fails); putInt(KEY_LOCKOUTS, lockouts); putLong(KEY_BLOCKED_UNTIL, blockedUntil) }
    }

    fun setAdmin(password: String) {
        adminHash = Secrets.encode(password)
        adminSet = true
        noteAdminEntry()
        prefs().edit { putString(KEY_ADMIN, adminHash) }
    }

    fun clearAdmin() {
        adminHash = null
        adminSet = false
        closeAdmin()
        prefs().edit { putString(KEY_ADMIN, null) }
    }

    fun setCombo(dirs: List<Dir>) {
        comboHash = Secrets.encode(dirs.joinToString("-") { it.name })
        comboSet = true
        comboLength = dirs.size
        prefs().edit { putString(KEY_COMBO, comboHash); putInt(KEY_COMBO_LEN, comboLength) }
    }

    fun clearCombo() {
        comboHash = null
        comboSet = false
        prefs().edit { putString(KEY_COMBO, null) }
    }

    /** The eight-character rule: letters and digits, both present. */
    fun adminFormatOk(password: String): Boolean =
        password.length == ADMIN_LENGTH && password.any { it.isDigit() } && password.any { it.isLetter() }

    const val ADMIN_LENGTH = 8
    const val MIN_COMBO = 4
    const val MAX_COMBO = 8

    // ---- Settings ----

    fun chooseEnabled(on: Boolean) {
        enabled = on
        prefs().edit { putBoolean(KEY_ENABLED, on) }
    }

    fun chooseLocked(packages: Set<String>) {
        lockedPackages = packages
        prefs().edit { putStringSet(KEY_LOCKED, packages) }
    }

    fun toggleLocked(pkg: String) {
        chooseLocked(if (pkg in lockedPackages) lockedPackages - pkg else lockedPackages + pkg)
    }

    fun saveGroup(group: Group) {
        groups = groups.toMutableList().apply {
            val at = indexOfFirst { it.id == group.id }
            if (at >= 0) this[at] = group else add(group)
        }
        persistGroups()
    }

    fun removeGroup(id: String) {
        groups = groups.filterNot { it.id == id }
        Usage.forget(id)
        persistGroups()
    }

    fun newGroupId(): String = "g" + System.currentTimeMillis().toString(36)

    private fun persistGroups() {
        prefs().edit { putString(KEY_GROUPS, groupsJson(groups)) }
    }

    /** Called by the services and screens when today's numbers moved. */
    fun touchUsage() { revision++ }

    // ---- Decisions ----

    fun groupOf(pkg: String): Group? = groups.firstOrNull { it.enabled && pkg in it.packages }

    fun isAppUnlocked(pkg: String): Boolean = pkg in unlocked

    fun markAppUnlocked(pkg: String) { unlocked += pkg }

    fun lockApp(pkg: String) { unlocked -= pkg }

    /**
     * Someone left [from] for [to] (null for Home): the combo entry for the app left expires, and if
     * the group changed the session there ends, so the break before the next one starts counting.
     */
    fun onForegroundChanged(from: String?, to: String?) {
        if (from == null || from == to) return
        lockApp(from)
        val was = groupOf(from) ?: return
        val next = to?.let { groupOf(it) }
        if (next?.id != was.id) {
            Usage.endSession(was.id)
            touchUsage()
        }
    }

    /**
     * The rules for [pkg] right now. [Verdict.Allow] for apps in no group and not locked, and for
     * everything while the whole feature is off.
     */
    fun verdict(pkg: String, now: Long = System.currentTimeMillis()): Verdict {
        if (!enabled) return Verdict.Allow
        val group = groupOf(pkg)
        val locked = pkg in lockedPackages
        val lockedVerdict = if (locked && !isAppUnlocked(pkg)) Verdict.Locked else Verdict.Allow
        if (group == null) return lockedVerdict

        Usage.rollDay(now)

        // A parent's pass covers every rule below it, for as long as it runs.
        if (Usage.passLeftSec(group.id, now) > 0) return lockedVerdict

        val rule = group.dayRule(now)
        if (!rule.enabled) return lockedVerdict

        val minute = minuteOfDay(now)
        if (rule.windows.isNotEmpty() && rule.windows.none { it.has(minute) }) {
            return Verdict.Blocked(group, Reason.Window, Usage.usedSec(group.id), rule.dailyMinutes, waitForWindow(rule.windows, minute))
        }

        val used = Usage.usedSec(group.id)
        if (rule.dailyMinutes > 0 && used >= rule.dailyMinutes * 60) {
            return Verdict.Blocked(group, Reason.Daily, used, rule.dailyMinutes, 0)
        }

        val breakLeft = Usage.breakMinutesLeft(group.id, rule.breakMinutes, now)
        if (breakLeft > 0) return Verdict.Blocked(group, Reason.Break, used, rule.dailyMinutes, breakLeft)

        val session = Usage.session(group.id)
        val sessionCap = rule.sessionMinutes * 60 + session.extraSec
        if (rule.sessionMinutes > 0 && session.usedSec >= sessionCap) {
            return Verdict.Blocked(group, Reason.Session, session.usedSec, rule.sessionMinutes, 0)
        }

        return lockedVerdict
    }

    /** Minutes until any window opens again (0 when one is open now). */
    private fun waitForWindow(windows: List<Window>, minute: Int): Int {
        if (windows.isEmpty()) return 0
        val next = windows.filter { it.start > minute }.minByOrNull { it.start }
        return if (next != null) next.start - minute else 24 * 60 - minute + windows.minOf { it.start }
    }

    // ---- JSON ----

    private fun groupsJson(groups: List<Group>): String = JSONArray().apply {
        groups.forEach { g ->
            put(JSONObject().apply {
                put("id", g.id)
                put("name", g.name)
                put("enabled", g.enabled)
                put("packages", JSONArray(g.packages.toList()))
                put("days", JSONArray().apply {
                    g.days.forEach { d ->
                        put(JSONObject().apply {
                            put("enabled", d.enabled)
                            put("daily", d.dailyMinutes)
                            put("session", d.sessionMinutes)
                            put("rest", d.breakMinutes)
                            put("windows", JSONArray().apply {
                                d.windows.forEach { w -> put(JSONArray().put(w.start).put(w.end)) }
                            })
                        })
                    }
                })
            })
        }
    }.toString()

    private fun parseGroups(json: String?): List<Group> {
        if (json.isNullOrBlank()) return emptyList()
        val arr = JSONArray(json)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val pkgs = o.optJSONArray("packages") ?: JSONArray()
            val dayArr = o.optJSONArray("days") ?: JSONArray()
            val days = (0 until 7).map { d ->
                val day = dayArr.optJSONObject(d) ?: return@map DayRule()
                val wins = day.optJSONArray("windows") ?: JSONArray()
                DayRule(
                    enabled = day.optBoolean("enabled", true),
                    dailyMinutes = day.optInt("daily", 60),
                    sessionMinutes = day.optInt("session", 30),
                    breakMinutes = day.optInt("rest", 10),
                    windows = (0 until wins.length()).mapNotNull { w ->
                        val pair = wins.optJSONArray(w) ?: return@mapNotNull null
                        Window(pair.optInt(0, 0), pair.optInt(1, 24 * 60)).takeIf { it.start < it.end }
                    },
                )
            }
            Group(
                id = o.optString("id").ifBlank { return@mapNotNull null },
                // A blank name stays blank: the five names the editor offers come from an array in
                // res/values, and this object has no context to look them up with.
                name = o.optString("name"),
                packages = (0 until pkgs.length()).map { pkgs.optString(it) }.filter { it.isNotBlank() }.toSet(),
                days = days,
                enabled = o.optBoolean("enabled", true),
            )
        }
    }
}
