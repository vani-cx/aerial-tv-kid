package app.orchard.tv.parental

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Today's numbers per group: seconds used, the minutes a parent or a maths question bought, the
 * session under way, when the last one ended and whether the maths was already used for it. Kept in
 * SharedPreferences ("parental_usage"), one day at a time, and only screen-on time is ever added
 * (see WatchService).
 *
 * The day rolls at local midnight. Everything is wall-clock time, so standby and reboots do not
 * lose the count; the flip side is that changing the TV's clock can move the day, which is noted in
 * docs/SECURITY-REVIEW.md.
 */
object Usage {
    private const val PREFS = "parental_usage"
    private var started = false
    private lateinit var app: Context

    /**
     * A continuous stretch of use of one group: it ends when the viewer leaves the group's apps or
     * when the session limit is reached. [hitAt] is when the limit was reached, so the rest that
     * follows counts down by the wall clock — leaving the app or coming back does not restart it.
     */
    data class Session(
        val startedAt: Long,
        var usedSec: Int = 0,
        /** Seconds the app has already given on top of the session limit (the maths question). */
        var extraSec: Int = 0,
        /** When the session limit was reached; 0 while the session may still run. */
        var hitAt: Long = 0L,
    )

    private var day: String = ""
    private val used = HashMap<String, Int>()
    /** Until when a parent has let this group through, whatever the rules say (wall clock). */
    private val passUntil = HashMap<String, Long>()
    /** Until when a maths answer outside the allowed windows lets the group through (wall clock). */
    private val quizPassUntil = HashMap<String, Long>()
    /** The day key on which the 15-minute question was already used, per group. */
    private val quizDay = HashMap<String, String>()
    private val sessions = HashMap<String, Session>()
    /** When the last session of a group ended: the rest counts from here. */
    private val lastEnd = HashMap<String, Long>()
    /** Groups whose current rest started at the session limit rather than by leaving. */
    private val restFromCap = HashSet<String>()
    /** The [lastEnd] value the 5-minute question was already used for. */
    private val restQuizAt = HashMap<String, Long>()

    fun init(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        load()
    }

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load() {
        val p = prefs()
        day = p.getString("day", "").orEmpty()
        used.clear(); passUntil.clear(); quizPassUntil.clear(); quizDay.clear()
        sessions.clear(); lastEnd.clear(); restFromCap.clear(); restQuizAt.clear()
        runCatching {
            JSONObject(p.getString("used", "{}") ?: "{}").let { o ->
                o.keys().forEach { k -> used[k] = o.optInt(k) }
            }
            JSONObject(p.getString("pass", "{}") ?: "{}").let { o ->
                o.keys().forEach { k -> passUntil[k] = o.optLong(k) }
            }
            JSONObject(p.getString("quiz_pass", "{}") ?: "{}").let { o ->
                o.keys().forEach { k -> quizPassUntil[k] = o.optLong(k) }
            }
            JSONObject(p.getString("quiz_day", "{}") ?: "{}").let { o ->
                o.keys().forEach { k -> quizDay[k] = o.optString(k) }
            }
            JSONObject(p.getString("sessions", "{}") ?: "{}").let { o ->
                o.keys().forEach { k ->
                    val s = o.optJSONObject(k) ?: return@forEach
                    sessions[k] = Session(
                        startedAt = s.optLong("at", 0L),
                        usedSec = s.optInt("used", 0),
                        extraSec = s.optInt("extra", 0),
                        hitAt = s.optLong("hit", 0L),
                    )
                }
            }
            JSONObject(p.getString("lastEnd", "{}") ?: "{}").let { o ->
                o.keys().forEach { k -> lastEnd[k] = o.optLong(k) }
            }
            JSONArray(p.getString("rest_cap", "[]") ?: "[]").let { a ->
                (0 until a.length()).forEach { restFromCap += a.optString(it) }
            }
            JSONObject(p.getString("rest_quiz", "{}") ?: "{}").let { o ->
                o.keys().forEach { k -> restQuizAt[k] = o.optLong(k) }
            }
        }
    }

    private fun persist() {
        prefs().edit {
            putString("day", day)
            putString("used", JSONObject().apply { used.forEach { (k, v) -> put(k, v) } }.toString())
            putString("pass", JSONObject().apply { passUntil.forEach { (k, v) -> put(k, v) } }.toString())
            putString("quiz_pass", JSONObject().apply { quizPassUntil.forEach { (k, v) -> put(k, v) } }.toString())
            putString("quiz_day", JSONObject().apply { quizDay.forEach { (k, v) -> put(k, v) } }.toString())
            putString(
                "sessions",
                JSONObject().apply {
                    sessions.forEach { (k, s) ->
                        put(k, JSONObject().apply {
                            put("at", s.startedAt); put("used", s.usedSec)
                            put("extra", s.extraSec); put("hit", s.hitAt)
                        })
                    }
                }.toString(),
            )
            putString("lastEnd", JSONObject().apply { lastEnd.forEach { (k, v) -> put(k, v) } }.toString())
            putString("rest_cap", JSONArray(restFromCap.toList()).toString())
            putString("rest_quiz", JSONObject().apply { restQuizAt.forEach { (k, v) -> put(k, v) } }.toString())
        }
    }

    /** Starts a new day (clearing today's numbers) when the date moved. */
    fun rollDay(now: Long = System.currentTimeMillis()) {
        val today = dayKey(now)
        if (today == day) return
        day = today
        used.clear(); passUntil.clear(); quizPassUntil.clear(); quizDay.clear()
        sessions.clear(); lastEnd.clear(); restFromCap.clear(); restQuizAt.clear()
        persist()
    }

    fun usedSec(groupId: String): Int = used[groupId] ?: 0

    fun usedMinutes(groupId: String): Int = (usedSec(groupId) + 59) / 60

    /** Seconds of a parent's pass still to run, 0 when there is none. */
    fun passLeftSec(groupId: String, now: Long = System.currentTimeMillis()): Int {
        val until = passUntil[groupId] ?: return 0
        val left = until - now
        if (left <= 0) {
            passUntil.remove(groupId)
            persist()
            return 0
        }
        return (left / 1000).toInt()
    }

    fun session(groupId: String): Session = sessions[groupId] ?: Session(0L)

    /** The viewer opened a group's app: a session starts here if none is under way. */
    fun beginSession(groupId: String, now: Long = System.currentTimeMillis()) {
        rollDay(now)
        if (sessions[groupId] == null) sessions[groupId] = Session(now)
    }

    /**
     * The viewer left the group's apps. A stretch shorter than a minute does not start the break:
     * an app that crashes, or a quick look at Home, should not cost the child a rest. A session the
     * limit already ended keeps the moment it hit, so leaving later does not lengthen the rest.
     */
    fun endSession(groupId: String, now: Long = System.currentTimeMillis()) {
        val s = sessions.remove(groupId) ?: return
        if (s.hitAt > 0) {
            lastEnd[groupId] = s.hitAt
        } else if (s.usedSec >= 60) {
            lastEnd[groupId] = now
            restFromCap -= groupId
        } else {
            persist()
            return
        }
        persist()
    }

    fun addUsed(groupId: String, seconds: Int, now: Long = System.currentTimeMillis()) {
        if (seconds <= 0) return
        rollDay(now)
        used[groupId] = usedSec(groupId) + seconds
        sessions[groupId]?.let { it.usedSec += seconds }
        persist()
    }

    /**
     * A parent's minutes from the 10…50 picker: the group goes through until [minutes] from now,
     * whatever the daily total, the session, the rest or the windows say. Use is still counted.
     */
    fun grantPass(groupId: String, minutes: Int, now: Long = System.currentTimeMillis()) {
        val until = maxOf(passUntil[groupId] ?: 0L, now) + minutes * 60_000L
        passUntil[groupId] = until
        persist()
        Parental.touchUsage()
    }

    // ---- The session limit and the rest ----

    /**
     * The session limit was reached: the rest starts now and runs by the clock. The session is kept
     * with the moment it hit, so the maths answer can add five minutes to it afterwards.
     */
    fun hitSession(groupId: String, breakMinutes: Int, now: Long = System.currentTimeMillis()) {
        if (breakMinutes <= 0) {
            restartSession(groupId, now)
            return
        }
        val s = sessions.getOrPut(groupId) { Session(now) }
        if (s.hitAt > 0) return
        s.hitAt = now
        lastEnd[groupId] = now
        restFromCap += groupId
        persist()
        Parental.touchUsage()
    }

    /** No rest configured: the next session starts at once. */
    fun restartSession(groupId: String, now: Long = System.currentTimeMillis()) {
        sessions[groupId] = Session(now)
        lastEnd.remove(groupId)
        restFromCap -= groupId
        persist()
    }

    /**
     * Seconds of rest still to run. When it runs out the spent session is dropped and a fresh one
     * starts, so the child gets a full session again without having to leave the app.
     */
    fun restLeftSec(groupId: String, breakMinutes: Int, now: Long = System.currentTimeMillis()): Int {
        if (breakMinutes <= 0) return 0
        val end = lastEnd[groupId] ?: return 0
        val leftMs = end + breakMinutes * 60_000L - now
        if (leftMs <= 0) {
            lastEnd.remove(groupId)
            restFromCap -= groupId
            sessions[groupId] = Session(now)
            persist()
            Parental.touchUsage()
            return 0
        }
        return ((leftMs + 999) / 1000).toInt()
    }

    /** The rest in force was started by the session limit, so the maths question applies. */
    fun restOffersQuiz(groupId: String): Boolean = groupId in restFromCap

    /** The 5-minute question was already used for the rest in force. */
    fun restQuizUsed(groupId: String): Boolean {
        val end = lastEnd[groupId] ?: return false
        return restQuizAt[groupId] == end
    }

    /**
     * The maths answer after a rest: five more minutes inside the same session, which then ends at
     * the limit again. Counted in the day's total like any other minute.
     */
    fun grantRestQuiz(groupId: String, minutes: Int, now: Long = System.currentTimeMillis()) {
        val s = sessions.getOrPut(groupId) { Session(now) }
        s.extraSec += minutes * 60
        s.hitAt = 0L
        restQuizAt[groupId] = lastEnd[groupId] ?: 0L
        lastEnd.remove(groupId)
        restFromCap -= groupId
        persist()
        Parental.touchUsage()
    }

    // ---- The windows ----

    /** Seconds of a maths bought pass outside the windows still to run. */
    fun quizPassLeftSec(groupId: String, now: Long = System.currentTimeMillis()): Int {
        val until = quizPassUntil[groupId] ?: return 0
        val left = until - now
        if (left <= 0) {
            quizPassUntil.remove(groupId)
            persist()
            return 0
        }
        return (left / 1000).toInt()
    }

    /** The 15-minute question is once a day per group. */
    fun quizExtraUsedToday(groupId: String, now: Long = System.currentTimeMillis()): Boolean =
        quizDay[groupId] == dayKey(now)

    /**
     * The maths answer outside the windows: [minutes] of wall-clock time that overrides the window,
     * the rest and the session — but not the day's total, which is checked first.
     */
    fun grantWindowQuiz(groupId: String, minutes: Int, now: Long = System.currentTimeMillis()) {
        quizDay[groupId] = dayKey(now)
        quizPassUntil[groupId] = now + minutes * 60_000L
        beginSession(groupId, now)
        persist()
        Parental.touchUsage()
    }

    /** A parent resets today's use of one group (after a wrong clock, say). */
    fun resetToday(groupId: String) {
        used.remove(groupId)
        passUntil.remove(groupId)
        quizPassUntil.remove(groupId)
        quizDay.remove(groupId)
        sessions.remove(groupId)
        lastEnd.remove(groupId)
        restFromCap -= groupId
        restQuizAt.remove(groupId)
        persist()
        Parental.touchUsage()
    }

    fun resetAll() {
        used.clear(); passUntil.clear(); quizPassUntil.clear(); quizDay.clear()
        sessions.clear(); lastEnd.clear(); restFromCap.clear(); restQuizAt.clear()
        persist()
        Parental.touchUsage()
    }

    /** A group is gone: drop what belongs to it. */
    fun forget(groupId: String) = resetToday(groupId)
}
