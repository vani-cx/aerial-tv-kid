package app.orchard.tv.parental

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** A D-pad direction: the alphabet of the children's direction passwords. */
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

enum class Reason { Daily, Window, Break }

/**
 * What happens when someone tries to open [pkg], or keeps using it.
 */
sealed interface Verdict {
    data object Allow : Verdict

    /** The app is locked: the app password (a direction combo) opens it. */
    data object Locked : Verdict

    data class Blocked(
        val group: Group,
        val reason: Reason,
        /** Seconds and minutes the group has used today, and its allowance, for the message. */
        val usedSec: Int,
        val limitMinutes: Int,
        /** Minutes until it opens again by itself; 0 when only a parent or the maths can help. */
        val waitMinutes: Int = 0,
        /** Seconds of rest still to run, for the countdown on the wall. */
        val restSec: Int = 0,
        /** Minute of the day the next allowed stretch starts (may be less than now: tomorrow). */
        val nextAtMinute: Int = -1,
        /** The maths question may buy time here. */
        val offerQuiz: Boolean = false,
        /** Minutes one right answer buys: 15 outside the windows, 5 after a rest. */
        val quizMinutes: Int = 0,
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
 * Parental controls: passwords, locked apps and time budgets (see docs/PARENTAL.md).
 *
 * Three passwords, with different jobs:
 *  - the **admin password**: eight characters, letters and digits mixed. It opens Settings (and the
 *    TV's own settings app), and it is the only way to change or delete either direction password.
 *  - the **app password**: a direction combo. It locks single apps and opens them again. Locking
 *    works with the children's mode off; changing or deleting the password needs the mode on.
 *  - the **time password**: a direction combo for the time wall, so a parent can give 10…50 minutes
 *    without typing the long admin one. It may be the same combo as the app password.
 *
 * State only, on the main thread; hashing runs off it (Secrets). Preferences: "parental".
 */
object Parental {
    private const val PREFS = "parental"
    private const val KEY_ADMIN = "admin_hash"
    private const val KEY_APP_COMBO = "app_combo_hash"
    private const val KEY_APP_COMBO_LEN = "app_combo_len"
    private const val KEY_KID_COMBO = "kid_combo_hash"
    private const val KEY_KID_COMBO_LEN = "kid_combo_len"
    private const val KEY_KID_SAME = "kid_combo_same"
    private const val KEY_AGE = "child_age"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LOCKED = "locked"
    private const val KEY_GROUPS = "groups"
    private const val KEY_FAILS = "fails"
    private const val KEY_LOCKOUTS = "lockouts"
    private const val KEY_BLOCKED_UNTIL = "blocked_until"

    /** How long one admin entry keeps Settings open, so a parent is not asked at every row. */
    private const val ADMIN_WINDOW_MS = 10 * 60 * 1000L

    /** Re-entry into the children's panel: free within a minute of leaving it, a password after. */
    private const val KID_WINDOW_MS = 60_000L

    /** Wrong entries allowed before waiting: a direction combo has too few combinations to guess at. */
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

    /** What one right answer buys, as the owner asked. */
    const val WINDOW_QUIZ_MINUTES = 15
    const val REST_QUIZ_MINUTES = 5

    /**
     * Leaving a locked app while a parent's pass is running relocks it after a minute, so a wrong
     * press on Home does not end the visit. Only the pass gets this; every other exit locks at once.
     */
    private const val RELOCK_GRACE_MS = 60_000L

    /** The ages the maths questions are picked for. */
    const val MIN_AGE = 3
    const val MAX_AGE = 12

    private var started = false
    private lateinit var app: Context

    var enabled by mutableStateOf(false); private set
    var adminSet by mutableStateOf(false); private set
    var appComboSet by mutableStateOf(false); private set
    var appComboLength by mutableStateOf(DEFAULT_COMBO) ; private set
    var kidComboSet by mutableStateOf(false); private set
    /** True while the time password is the app password (the default). */
    var kidComboSame by mutableStateOf(true); private set
    var childAge by mutableStateOf(6); private set
    var groups by mutableStateOf<List<Group>>(emptyList()); private set
    var lockedPackages by mutableStateOf<Set<String>>(emptySet()); private set

    /** Bumped whenever today's usage changes, so the settings screen redraws its numbers. */
    var revision by mutableStateOf(0); private set

    private var adminHash: String? = null
    private var appComboHash: String? = null
    private var kidComboHash: String? = null
    private var kidComboLength = DEFAULT_COMBO
    private var adminAt = 0L
    /** When the children's panel was last left; see [kidOpen]. */
    private var kidAt = 0L
    private var fails = 0

    /** Rounds of [MAX_FAILS] wrong entries so far, each doubling the wait before the next try. */
    private var lockouts = 0
    private var blockedUntil = 0L

    /** Apps opened with the password this session; dropped when the viewer leaves them. */
    private val unlocked = HashSet<String>()

    /**
     * When an app locks itself again after the viewer left it while a parent's pass was running,
     * keyed by package. A pass means the grown-up said yes, so leaving the app (Home by mistake, an
     * input switch and back) gets [RELOCK_GRACE_MS] to return before the password is asked for
     * again; the owner asked for that. Every other way out — the screen going off, a restart — still
     * locks at once.
     */
    private val relockAt = HashMap<String, Long>()

    fun init(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        val p = prefs()
        adminHash = p.getString(KEY_ADMIN, null)
        appComboHash = p.getString(KEY_APP_COMBO, null)
        kidComboHash = p.getString(KEY_KID_COMBO, null)
        appComboLength = p.getInt(KEY_APP_COMBO_LEN, DEFAULT_COMBO).coerceIn(MIN_COMBO, MAX_COMBO)
        kidComboLength = p.getInt(KEY_KID_COMBO_LEN, DEFAULT_COMBO).coerceIn(MIN_COMBO, MAX_COMBO)
        kidComboSame = p.getBoolean(KEY_KID_SAME, true)
        childAge = p.getInt(KEY_AGE, 6).coerceIn(MIN_AGE, MAX_AGE)
        // One combo before 1.36: it becomes both the app and the time password.
        val old = p.getString("combo_hash", null)
        if (old != null && appComboHash == null) {
            appComboHash = old
            kidComboHash = old
            appComboLength = p.getInt("combo_len", DEFAULT_COMBO).coerceIn(MIN_COMBO, MAX_COMBO)
            kidComboLength = appComboLength
            kidComboSame = true
            p.edit {
                putString(KEY_APP_COMBO, old); putString(KEY_KID_COMBO, old)
                putInt(KEY_APP_COMBO_LEN, appComboLength); putInt(KEY_KID_COMBO_LEN, kidComboLength)
                putBoolean(KEY_KID_SAME, true)
                remove("combo_hash"); remove("combo_len")
            }
        }
        adminSet = adminHash != null
        appComboSet = appComboHash != null
        kidComboSet = kidComboHash != null
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

    /**
     * The children's panel has its own, much shorter window: a parent who leaves it and comes back
     * within [KID_WINDOW_MS] is not asked again, and anything longer is. The clock starts at the
     * moment the panel is left, not at the moment the password was typed, so a long visit does not
     * spend the grace while the parent is still sitting in it.
     */
    val kidOpen: Boolean get() = System.currentTimeMillis() < kidAt

    fun noteKidEntry() { kidAt = System.currentTimeMillis() + KID_WINDOW_MS }

    fun noteKidExit() {
        if (kidAt != 0L) kidAt = System.currentTimeMillis() + KID_WINDOW_MS
    }

    fun closeAdmin() {
        adminAt = 0L
        kidAt = 0L
    }

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

    /** Checks the app-lock combo. Runs a hash of the whole sequence: call it off the main thread. */
    fun verifyAppCombo(dirs: List<Dir>): Boolean = verifySeq(dirs, appComboLength, appComboHash)

    /**
     * Checks the time wall's combo. It is the app combo unless the parent set a second one, so a
     * household with one password types it in both places.
     */
    fun verifyKidCombo(dirs: List<Dir>): Boolean =
        if (kidComboSame || kidComboHash == null) verifyAppCombo(dirs)
        else verifySeq(dirs, kidComboLength, kidComboHash)

    private fun verifySeq(dirs: List<Dir>, length: Int, hash: String?): Boolean {
        if (waitMs() > 0) return false
        if (dirs.size != length) return false
        val ok = Secrets.verify(dirs.joinToString("-") { it.name }, hash)
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

    fun setAppCombo(dirs: List<Dir>) {
        appComboHash = Secrets.encode(dirs.joinToString("-") { it.name })
        appComboSet = true
        appComboLength = dirs.size
        prefs().edit { putString(KEY_APP_COMBO, appComboHash); putInt(KEY_APP_COMBO_LEN, appComboLength) }
    }

    fun clearAppCombo() {
        appComboHash = null
        appComboSet = false
        prefs().edit { putString(KEY_APP_COMBO, null) }
    }

    fun setKidCombo(dirs: List<Dir>) {
        kidComboHash = Secrets.encode(dirs.joinToString("-") { it.name })
        kidComboSet = true
        kidComboLength = dirs.size
        kidComboSame = false
        prefs().edit {
            putString(KEY_KID_COMBO, kidComboHash); putInt(KEY_KID_COMBO_LEN, kidComboLength)
            putBoolean(KEY_KID_SAME, false)
        }
    }

    fun clearKidCombo() {
        kidComboHash = null
        kidComboSet = false
        prefs().edit { putString(KEY_KID_COMBO, null) }
    }

    /** Back to one password for both doors. */
    fun chooseKidComboSame(on: Boolean) {
        kidComboSame = on
        if (on) {
            kidComboHash = null
            kidComboSet = false
        }
        prefs().edit { putBoolean(KEY_KID_SAME, on); if (on) putString(KEY_KID_COMBO, null) }
    }

    /** The combo the time wall asks for, as its length; 0 when nobody has set one. */
    val kidComboLengthShown: Int get() = if (kidComboSame || kidComboHash == null) appComboLength else kidComboLength

    /** The time wall has a password of its own or through the app's. */
    val kidDoorSet: Boolean get() = kidComboSame && appComboSet || kidComboHash != null

    fun setAge(age: Int) {
        childAge = age.coerceIn(MIN_AGE, MAX_AGE)
        prefs().edit { putInt(KEY_AGE, childAge) }
    }

    /** The eight-character rule: letters and digits, both present. */
    fun adminFormatOk(password: String): Boolean =
        password.length == ADMIN_LENGTH && password.any { it.isDigit() } && password.any { it.isLetter() }

    const val ADMIN_LENGTH = 8
    const val MIN_COMBO = 4
    const val MAX_COMBO = 8
    private const val DEFAULT_COMBO = 4

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

    /** Open with the password, or still inside the minute a pass gave after leaving it. */
    fun isAppUnlocked(pkg: String, now: Long = System.currentTimeMillis()): Boolean {
        val until = relockAt[pkg] ?: return pkg in unlocked
        if (now < until) return true
        relockAt -= pkg
        unlocked -= pkg
        return false
    }

    fun markAppUnlocked(pkg: String) {
        relockAt -= pkg
        unlocked += pkg
    }

    fun lockApp(pkg: String) {
        relockAt -= pkg
        unlocked -= pkg
    }

    /**
     * The app lock's auto-relock rule, as the owner asked: the password covers one visit. Going
     * back to Home, switching to another app, the screen going off and a restart all lock again.
     * A minute of grace applies while a parent's pass is running (see [RELOCK_GRACE_MS]).
     */
    fun lockAll() {
        unlocked.clear()
        relockAt.clear()
    }

    /**
     * Someone left [from] for [to] (null for Home): the password entry for the app left expires, and
     * if the group changed the session there ends, so the break before the next one starts counting.
     */
    fun onForegroundChanged(from: String?, to: String?) {
        if (from == null || from == to) return
        val was = groupOf(from)
        val now = System.currentTimeMillis()
        if (was != null && from in unlocked && Usage.passLeftSec(was.id, now) > 0) {
            // A grown-up said yes to this group: a minute to come back before the lock returns.
            relockAt[from] = now + RELOCK_GRACE_MS
        } else {
            lockApp(from)
        }
        if (was == null) return
        val next = to?.let { groupOf(it) }
        if (next?.id != was.id) {
            Usage.endSession(was.id)
            touchUsage()
        }
    }

    /**
     * The rules for [pkg] right now. [Verdict.Allow] for apps in no group and not locked, and for
     * the time rules while the children's mode is off (the app lock still applies then).
     */
    fun verdict(pkg: String, now: Long = System.currentTimeMillis()): Verdict {
        val lockedVerdict: Verdict =
            if (pkg in lockedPackages && !isAppUnlocked(pkg)) Verdict.Locked else Verdict.Allow
        if (!enabled) return lockedVerdict
        val group = groupOf(pkg) ?: return lockedVerdict

        Usage.rollDay(now)

        // A parent's pass covers every time rule below it, for as long as it runs.
        if (Usage.passLeftSec(group.id, now) > 0) return lockedVerdict

        val rule = group.dayRule(now)
        if (!rule.enabled) return lockedVerdict

        val used = Usage.usedSec(group.id)

        // The day comes first: once it is gone neither the maths nor the windows help.
        if (rule.dailyMinutes > 0 && used >= rule.dailyMinutes * 60) {
            return Verdict.Blocked(group, Reason.Daily, used, rule.dailyMinutes)
        }

        // Minutes bought with a maths question outside the windows: they sit over the window, the
        // rest and the session, but never over the day above.
        if (Usage.quizPassLeftSec(group.id, now) > 0) return lockedVerdict

        val minute = minuteOfDay(now)
        if (rule.windows.isNotEmpty() && rule.windows.none { it.has(minute) }) {
            val next = nextWindow(rule.windows, minute)
            return Verdict.Blocked(
                group, Reason.Window, used, rule.dailyMinutes,
                waitMinutes = waitFor(next, minute),
                nextAtMinute = next,
                offerQuiz = !Usage.quizExtraUsedToday(group.id, now),
                quizMinutes = WINDOW_QUIZ_MINUTES,
            )
        }

        val rest = Usage.restLeftSec(group.id, rule.breakMinutes, now)
        if (rest > 0) {
            return Verdict.Blocked(
                group, Reason.Break, used, rule.dailyMinutes,
                waitMinutes = (rest + 59) / 60,
                restSec = rest,
                offerQuiz = Usage.restOffersQuiz(group.id) && !Usage.restQuizUsed(group.id),
                quizMinutes = REST_QUIZ_MINUTES,
            )
        }

        val session = Usage.session(group.id)
        if (rule.sessionMinutes > 0 && session.usedSec >= rule.sessionMinutes * 60 + session.extraSec) {
            // No rest configured: straight into a new session, no wall to show.
            if (rule.breakMinutes <= 0) {
                Usage.restartSession(group.id, now)
                return lockedVerdict
            }
            // The session is over where it stands: the rest starts now, and counts down by the
            // wall clock whether the child stays on the wall, goes Home or comes back later.
            Usage.hitSession(group.id, rule.breakMinutes, now)
            val left = Usage.restLeftSec(group.id, rule.breakMinutes, now)
            return Verdict.Blocked(
                group, Reason.Break, used, rule.dailyMinutes,
                waitMinutes = (left + 59) / 60,
                restSec = left,
                offerQuiz = left > 0 && !Usage.restQuizUsed(group.id),
                quizMinutes = REST_QUIZ_MINUTES,
            )
        }

        return lockedVerdict
    }

    /** Start minute of the next allowed stretch: today if it comes, otherwise tomorrow's first. */
    private fun nextWindow(windows: List<Window>, minute: Int): Int =
        windows.filter { it.start > minute }.minByOrNull { it.start }?.start ?: windows.minOf { it.start }

    private fun waitFor(nextStart: Int, minute: Int): Int =
        if (nextStart > minute) nextStart - minute else 24 * 60 - minute + nextStart

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
