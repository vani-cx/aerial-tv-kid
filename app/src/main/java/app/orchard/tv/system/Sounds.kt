package app.orchard.tv.system

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import android.view.KeyEvent
import app.orchard.tv.R
import app.orchard.tv.settings.AerialSettings

/**
 * Interface sounds, as tvOS plays them: a tick when focus moves or a slider steps, a tock on select
 * and on a long press, a softer one on Back, a bump at the edge of the grid or a slider. The
 * sounds are Aerial's own (tools/make-sounds.py).
 *
 * Focus sounds play only for a move the remote asked for: focus that lands somewhere because a
 * panel opened or Home reset is silent, as on tvOS. One tick per key press, however many controls
 * see focus change on the way.
 */
object Sounds {
    private var pool: SoundPool? = null
    private var move = 0
    private var select = 0
    private var back = 0
    private var limit = 0
    private var lastDpadAt = 0L
    private var tickedFor = -1L

    fun init(context: Context) {
        if (pool != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        pool = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(attrs).build().also {
            move = it.load(context, R.raw.ui_move, 1)
            select = it.load(context, R.raw.ui_select, 1)
            back = it.load(context, R.raw.ui_back, 1)
            limit = it.load(context, R.raw.ui_limit, 1)
        }
    }

    /** Every key the activity sees, before Compose does. */
    fun onKey(event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> lastDpadAt = event.eventTime
            KeyEvent.KEYCODE_BACK -> if (event.repeatCount == 0) play(back, 0.9f)
        }
    }

    /** A control took focus. */
    fun focusMoved() {
        val at = lastDpadAt
        if (at == tickedFor || SystemClock.uptimeMillis() - at > 250) return
        tickedFor = at
        play(move, 0.7f)
    }

    /** A D-pad press that had nowhere to go. */
    fun limit() {
        tickedFor = lastDpadAt
        play(limit, 0.9f)
    }

    fun select() = play(select, 1f)

    /** A slider moved one step (volume, brightness). */
    fun step() = play(move, 0.7f)

    private fun play(id: Int, volume: Float) {
        if (!AerialSettings.uiSounds || id == 0) return
        pool?.play(id, volume, volume, 1, 0, 1f)
    }
}
