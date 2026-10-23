package app.orchard.tv.parental

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

/**
 * Today's numbers per group: seconds used, minutes a parent added, the session under way and when
 * the last one ended. Kept in SharedPreferences ("parental_usage"), one day at a time, and only
 * screen-on time is ever added (see WatchService).
 *
 * The day rolls at local midnight. Everything is wall-clock time, so standby and reboots do not
 * lose the count; the flip side is that changing the TV's clock can move the day, which is noted in
 * docs/SECURITY-REVIEW.md.
 */
object Usage {
    private const val PREFS = "parental_usage"
    private var started = false
    private lateinit var app: Context

    /** A continuous stretch of use of one group: it ends when the viewer leaves the group's apps. */
    data class Session(
        val startedAt: Long,
        var usedSec: Int = 0,
        /** Extra seconds granted inside this session (the maths question, a parent's minutes). */
        var extraSec: Int = 0,
        /** The one maths question a session is allowed. */
        var quizUsed: Boolean = false,
    )

    private var day: String = ""
    private val used = HashMap<String, Int>()
    /** Until when a parent has let this group through, whatever the rules say (wall clock). */
    private val passUntil = HashMap<String, Long>()
    private val sessions = HashMap<String, Session>()
    private val lastEnd = HashMap<String, Long>()

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
        used.clear(); passUntil.clear(); sessions.clear(); lastEnd.clear()
        runCatching {
            JSONObject(p.getString("used", "{}") ?: "{}").let { o ->
                o.keys().forEach { k -> used[k] = o.optInt(k) }
            }
            JSONObject(p.getString("pass", "{}") ?: "{}").let { o ->
                o.keys().forEach { k -> passUntil[k] = o.optLong(k) }
            }
            JSONObject(p.getString("sessions", "{}") ?: "{}").let { o ->
                o.keys().forEach { k ->
                    val s = o.optJSONObject(k) ?: return@forEach
                    sessions[k] = Session(
                        startedAt = s.optLong("at", 0L),
                        usedSec = s.optInt("used", 0),
                        extraSec = s.optInt("extra", 0),
                        quizUsed = s.optBoolean("quiz", false),
                    )
                }
            }
            JSONObject(p.getString("lastEnd", "{}") ?: "{}").let { o ->
                o.keys().forEach { k -> lastEnd[k] = o.optLong(k) }
            }
        }
    }

    private fun persist() {
        prefs().edit {
            putString("day", day)
            putString("used", JSONObject().apply { used.forEach { (k, v) -> put(k, v) } }.toString())
            putString("pass", JSONObject().apply { passUntil.forEach { (k, v) -> put(k, v) } }.toString())
            putString(
                "sessions",
                JSONObject().apply {
                    sessions.forEach { (k, s) ->
                        put(k, JSONObject().apply {
                            put("at", s.startedAt); put("used", s.usedSec)
                            put("extra", s.extraSec); put("quiz", s.quizUsed)
                        })
                    }
                }.toString(),
            )
            putString("lastEnd", JSONObject().apply { lastEnd.forEach { (k, v) -> put(k, v) } }.toString())
        }
    }

    /** Starts a new day (clearing today's numbers) when the date moved. */
    fun rollDay(now: Long = System.currentTimeMillis()) {
        val today = dayKey(now)
        if (today == day) return
        day = today
        used.clear(); passUntil.clear(); sessions.clear(); lastEnd.clear()
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
     * an app that crashes, or a quick look at Home, should not cost the child a rest.
     */
    fun endSession(groupId: String, now: Long = System.currentTimeMillis()) {
        val s = sessions.remove(groupId) ?: return
        if (s.usedSec >= 60) lastEnd[groupId] = now
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
     * whatever the daily total, the session, the break or the windows say. Use is still counted.
     */
    fun grantPass(groupId: String, minutes: Int, now: Long = System.currentTimeMillis()) {
        val until = maxOf(passUntil[groupId] ?: 0L, now) + minutes * 60_000L
        passUntil[groupId] = until
        persist()
        Parental.touchUsage()
    }

    /**
     * The maths question's five minutes, inside this session only. A session that was ended (Home
     * pressed, the app killed) starts again here, so the answer is never wasted.
     */
    fun extendSession(groupId: String, minutes: Int) {
        val session = sessions.getOrPut(groupId) { Session(System.currentTimeMillis()) }
        session.extraSec += minutes * 60
        session.quizUsed = true
        persist()
        Parental.touchUsage()
    }

    fun quizUsed(groupId: String): Boolean = sessions[groupId]?.quizUsed == true

    /** Whole minutes of rest still owed before a new session may start. */
    fun breakMinutesLeft(groupId: String, breakMinutes: Int, now: Long = System.currentTimeMillis()): Int {
        if (breakMinutes <= 0 || groupId !in lastEnd) return 0
        val leftMs = lastEnd.getValue(groupId) + breakMinutes * 60_000L - now
        return if (leftMs <= 0) 0 else ((leftMs + 59_999L) / 60_000L).toInt()
    }

    /** A parent resets today's use of one group (after a wrong clock, say). */
    fun resetToday(groupId: String) {
        used.remove(groupId)
        passUntil.remove(groupId)
        sessions.remove(groupId)
        lastEnd.remove(groupId)
        persist()
        Parental.touchUsage()
    }

    fun resetAll() {
        used.clear(); passUntil.clear(); sessions.clear(); lastEnd.clear()
        persist()
        Parental.touchUsage()
    }

    /** A group is gone: drop what belongs to it. */
    fun forget(groupId: String) = resetToday(groupId)
}
