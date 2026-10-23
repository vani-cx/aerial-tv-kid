package app.orchard.tv.ui.parental

import android.content.Context
import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orchard.tv.R
import app.orchard.tv.parental.Dir
import app.orchard.tv.parental.Parental
import app.orchard.tv.parental.Reason
import app.orchard.tv.parental.Usage
import app.orchard.tv.parental.Verdict
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.GlyphIcon
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * The block screen: why the app stopped, then the way out — the maths question (five minutes, once
 * a session), a parent's password (10…50 minutes), or the direction combo for a locked app.
 *
 * Compose UI on Aerial's 960×540 canvas, hosted by [app.orchard.tv.parental.BlockActivity] over an
 * app that is already playing, and by HomeScreen as an overlay before an app opens.
 */
@Composable
fun ParentalWallHost(
    pkg: String,
    appLabel: String,
    lockMode: Boolean,
    onAllowed: () -> Unit,
    onCancel: () -> Unit,
) {
    var verdict by remember { mutableStateOf(Parental.verdict(pkg)) }
    var step by remember { mutableStateOf(Step.Ask) }
    var combo by remember { mutableStateOf(emptyList<Dir>()) }
    var password by remember { mutableStateOf("") }
    var wrongCombo by remember { mutableStateOf(false) }
    var wrongAdmin by remember { mutableStateOf(false) }
    var wrongMath by remember { mutableStateOf(false) }
    var waitSec by remember { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()
    val rootFocus = remember { FocusRequester() }
    val firstAction = remember { FocusRequester() }

    // The rules move while the wall is up: a break ends, a pass runs out, a parent opens the app
    // from elsewhere. One look a second closes the wall by itself when it no longer applies.
    LaunchedEffect(pkg) {
        while (true) {
            val now = Parental.verdict(pkg)
            if (now is Verdict.Allow) {
                onAllowed()
                return@LaunchedEffect
            }
            verdict = now
            waitSec = Parental.waitMs() / 1000
            delay(1_000)
        }
    }

    // A locked app asks for the combo straight away; a spent budget explains itself first.
    LaunchedEffect(pkg) {
        if (lockMode && Parental.comboSet) step = Step.Combo
    }

    // The pad, the keypad and the buttons all take focus through this: the step decides who gets it.
    LaunchedEffect(step) {
        withFrameNanos { }
        if (step == Step.Combo) runCatching { rootFocus.requestFocus() }
        if (step == Step.Ask || step == Step.Math || step == Step.Pass) runCatching { firstAction.requestFocus() }
    }

    BackHandler {
        when (step) {
            Step.Ask -> onCancel()
            else -> {
                step = Step.Ask
                combo = emptyList()
                password = ""
            }
        }
    }

    val blocked = verdict as? Verdict.Blocked
    val group = blocked?.group

    fun allowIfPossible() {
        if (Parental.verdict(pkg) is Verdict.Allow) onAllowed() else {
            step = Step.Ask
            combo = emptyList()
            password = ""
        }
    }

    fun submitCombo(dirs: List<Dir>) {
        scope.launch {
            val ok = withContext(Dispatchers.Default) { Parental.verifyCombo(dirs) }
            combo = emptyList()
            if (ok) {
                Parental.markAppUnlocked(pkg)
                wrongCombo = false
                allowIfPossible()
            } else {
                wrongCombo = true
                waitSec = Parental.waitMs() / 1000
            }
        }
    }

    fun submitPassword(entered: String) {
        scope.launch {
            val ok = withContext(Dispatchers.Default) { Parental.verifyAdmin(entered) }
            password = ""
            if (ok) {
                wrongAdmin = false
                step = Step.Pass
            } else {
                wrongAdmin = true
                waitSec = Parental.waitMs() / 1000
            }
        }
    }

    fun onKey(e: KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown) return false
        val code = e.nativeKeyEvent.keyCode
        when (step) {
            Step.Combo -> {
                if (e.nativeKeyEvent.repeatCount > 0) return false
                val length = Parental.comboLength
                when (code) {
                    NativeKeyEvent.KEYCODE_DPAD_UP -> combo = add(combo, Dir.Up, length)
                    NativeKeyEvent.KEYCODE_DPAD_DOWN -> combo = add(combo, Dir.Down, length)
                    NativeKeyEvent.KEYCODE_DPAD_LEFT -> combo = add(combo, Dir.Left, length)
                    NativeKeyEvent.KEYCODE_DPAD_RIGHT -> combo = add(combo, Dir.Right, length)
                    NativeKeyEvent.KEYCODE_DEL -> combo = combo.dropLast(1)
                    NativeKeyEvent.KEYCODE_DPAD_CENTER, NativeKeyEvent.KEYCODE_ENTER, NativeKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (combo.size == length) submitCombo(combo)
                    }
                    else -> return false
                }
                return true
            }
            Step.Admin -> {
                // Only character keys: the D-pad still moves between the keys on screen.
                val next = adminEdit(password, code) ?: return false
                password = next
                return true
            }
            else -> return false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f))
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent(::onKey),
        contentAlignment = Alignment.Center,
    ) {
        Panel(width = if (step == Step.Admin) 700.dp else 640.dp) {
            GlyphIcon(
                when (step) {
                    Step.Combo -> Glyph.Switches
                    Step.Admin -> Glyph.Shield
                    Step.Math -> Glyph.Check
                    else -> Glyph.Moon
                },
                Palette.SecondaryLabel, 28.dp,
            )
            Spacer(Modifier.height(10.dp))
            when (step) {
                Step.Ask -> Ask(
                    verdict = verdict,
                    appLabel = appLabel,
                    firstAction = firstAction,
                    onMath = { wrongMath = false; step = Step.Math },
                    onCombo = { wrongCombo = false; combo = emptyList(); step = Step.Combo },
                    onParent = { wrongAdmin = false; password = ""; step = Step.Admin },
                    onHome = onCancel,
                )

                Step.Combo -> ComboPad(
                    entered = combo,
                    length = Parental.comboLength,
                    error = when {
                        waitSec > 0 -> waitMessage(LocalContext.current, waitSec)
                        wrongCombo -> stringResource(R.string.kid_combo_wrong)
                        else -> null
                    },
                    onTap = { dir -> combo = add(combo, dir, Parental.comboLength) },
                    onConfirm = { if (combo.size == Parental.comboLength) submitCombo(combo) },
                )

                Step.Admin -> AdminPad(
                    entered = password,
                    wrong = wrongAdmin && waitSec == 0L,
                    message = when {
                        waitSec > 0 -> waitMessage(LocalContext.current, waitSec)
                        wrongAdmin -> stringResource(R.string.kid_admin_wrong)
                        else -> stringResource(R.string.kid_admin_hint)
                    },
                    onType = { password = typeChar(password, it) },
                    onDelete = { password = password.dropLast(1) },
                    onSubmit = { if (password.length == Parental.ADMIN_LENGTH) submitPassword(password) },
                )

                Step.Pass -> PassPicker(
                    onPick = { minutes ->
                        group?.let { Usage.grantPass(it.id, minutes) }
                        Parental.markAppUnlocked(pkg)
                        allowIfPossible()
                    },
                )

                Step.Math -> MathPad(
                    wrong = wrongMath,
                    firstAction = firstAction,
                    onWrong = { wrongMath = true },
                    onRight = {
                        group?.let { Usage.extendSession(it.id, 5) }
                        allowIfPossible()
                    },
                )
            }
            Spacer(Modifier.height(14.dp))
            BasicText(
                when (step) {
                    Step.Ask -> stringResource(if (Parental.adminSet) R.string.kid_wall_ask_hint else R.string.kid_wall_no_admin)
                    Step.Combo -> stringResource(R.string.kid_combo_hint)
                    Step.Pass -> stringResource(R.string.kid_wall_pick_minutes_hint)
                    else -> stringResource(R.string.kid_wall_back_hint)
                },
                style = Type.Caption.copy(textAlign = TextAlign.Center),
            )
        }
    }
}

private enum class Step { Ask, Combo, Admin, Pass, Math }

internal fun typeChar(password: String, ch: Char): String =
    if (password.length < Parental.ADMIN_LENGTH) password + ch else password

/** The new password after one remote key, or null when the key is not a character. */
internal fun adminEdit(password: String, code: Int): String? = when {
    code in NativeKeyEvent.KEYCODE_0..NativeKeyEvent.KEYCODE_9 ->
        typeChar(password, '0' + (code - NativeKeyEvent.KEYCODE_0))
    code in NativeKeyEvent.KEYCODE_A..NativeKeyEvent.KEYCODE_Z ->
        typeChar(password, 'A' + (code - NativeKeyEvent.KEYCODE_A))
    code == NativeKeyEvent.KEYCODE_DEL -> password.dropLast(1)
    else -> null
}

/** The combo after one direction key, or null when the key is not a direction. */
internal fun comboEdit(entered: List<Dir>, code: Int, length: Int): List<Dir>? {
    val dir = when (code) {
        NativeKeyEvent.KEYCODE_DPAD_UP -> Dir.Up
        NativeKeyEvent.KEYCODE_DPAD_DOWN -> Dir.Down
        NativeKeyEvent.KEYCODE_DPAD_LEFT -> Dir.Left
        NativeKeyEvent.KEYCODE_DPAD_RIGHT -> Dir.Right
        else -> return null
    }
    return if (entered.size < length) entered + dir else entered
}

internal fun add(dirs: List<Dir>, dir: Dir, length: Int): List<Dir> =
    if (dirs.size < length) dirs + dir else dirs

/**
 * "Wait 30 seconds" or, after the wait has doubled a few times, "wait 4 minutes": both the wall and
 * the settings sheet say it the same way.
 */
internal fun waitMessage(context: Context, waitSec: Long): String =
    if (waitSec >= 60) context.getString(R.string.kid_wait_minutes, ((waitSec + 59) / 60).toInt())
    else context.getString(R.string.kid_admin_wait, waitSec)

@Composable
private fun Panel(width: Dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .width(width)
            .clip(RoundedCornerShape(28.dp))
            .background(Palette.Glass)
            .border(1.dp, Palette.Hairline, RoundedCornerShape(28.dp))
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

/** "25 minutes" / "1 hour 5 minutes", for the numbers the rules talk in. */
@Composable
private fun duration(seconds: Int): String {
    val minutes = (seconds + 59) / 60
    return when {
        minutes < 60 -> stringResource(R.string.kid_minutes, minutes)
        minutes % 60 == 0 -> stringResource(R.string.kid_hours, minutes / 60)
        else -> stringResource(R.string.kid_hours_minutes, minutes / 60, minutes % 60)
    }
}

/** The message and the ways out, for the rule that is in force. */
@Composable
private fun ColumnScope.Ask(
    verdict: Verdict,
    appLabel: String,
    firstAction: FocusRequester,
    onMath: () -> Unit,
    onCombo: () -> Unit,
    onParent: () -> Unit,
    onHome: () -> Unit,
) {
    val blocked = verdict as? Verdict.Blocked
    val locked = verdict is Verdict.Locked
    val title = when {
        blocked == null || locked -> stringResource(R.string.kid_wall_locked_title)
        blocked.reason == Reason.Daily -> stringResource(R.string.kid_wall_daily_title)
        blocked.reason == Reason.Session -> stringResource(R.string.kid_wall_session_title)
        blocked.reason == Reason.Break -> stringResource(R.string.kid_wall_break_title)
        else -> stringResource(R.string.kid_wall_window_title)
    }
    val body = when {
        blocked == null || locked -> stringResource(R.string.kid_wall_locked_body, appLabel)
        blocked.reason == Reason.Daily -> stringResource(
            R.string.kid_wall_daily_body, appLabel, duration(blocked.usedSec), duration(blocked.limitMinutes * 60),
        )
        blocked.reason == Reason.Session -> stringResource(
            R.string.kid_wall_session_body, appLabel, duration(blocked.usedSec), duration(blocked.limitMinutes * 60),
        )
        blocked.reason == Reason.Break -> stringResource(R.string.kid_wall_break_body, appLabel, duration(blocked.waitMinutes * 60))
        else -> stringResource(R.string.kid_wall_window_body, appLabel, duration(blocked.waitMinutes * 60))
    }
    BasicText(title, style = Type.LargeTitle.copy(textAlign = TextAlign.Center))
    Spacer(Modifier.height(8.dp))
    BasicText(body, style = Type.Body.copy(color = Palette.SecondaryLabel, textAlign = TextAlign.Center))

    val sessionGroup = if (blocked?.reason == Reason.Session) blocked?.group else null
    val quizLeft = sessionGroup != null && !Usage.quizUsed(sessionGroup.id)
    val comboOffered = locked && Parental.comboSet
    val parentOffered = Parental.adminSet

    Spacer(Modifier.height(20.dp))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        var firstTaken = false
        fun nextRequester(): FocusRequester? = if (firstTaken) null else { firstTaken = true; firstAction }

        if (quizLeft) {
            WallButton(stringResource(R.string.kid_wall_math), primary = true, focusRequester = nextRequester(), onClick = onMath)
        } else if (blocked?.reason == Reason.Session) {
            BasicText(stringResource(R.string.kid_wall_math_used), style = Type.Caption.copy(color = Palette.TertiaryLabel))
        }
        if (comboOffered) {
            WallButton(stringResource(R.string.kid_wall_combo), primary = blocked == null, focusRequester = nextRequester(), onClick = onCombo)
        }
        if (parentOffered) {
            WallButton(
                stringResource(R.string.kid_wall_parent),
                primary = blocked != null && !quizLeft && !comboOffered,
                focusRequester = nextRequester(),
                onClick = onParent,
            )
        }
        // Always the way out: Back does the same thing, but a child should not have to guess.
        WallButton(stringResource(R.string.kid_wall_home), focusRequester = nextRequester(), onClick = onHome)
    }
}

@Composable
private fun WallButton(
    label: String,
    primary: Boolean = false,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    Pressable(onClick = onClick, focusRequester = focusRequester, focusedScale = 1.05f) { state ->
        val bg by animateColorAsState(
            when {
                state.focused -> Palette.Focus
                primary -> Palette.FillStrong
                else -> Palette.Fill
            },
            tween(140, easing = Motion.EaseOut), label = "wall-button",
        )
        val fg = if (state.focused) Palette.OnFocus else Palette.Label
        Box(
            Modifier.clip(RoundedCornerShape(50)).background(bg).height(44.dp).padding(horizontal = 26.dp),
            contentAlignment = Alignment.Center,
        ) { BasicText(label, style = Type.Headline.copy(color = fg)) }
    }
}

// ---- The direction combo ----

/**
 * The pad for the combo. Every direction key is taken at the wall's root before the focus system
 * sees it (see [ParentalWallHost]'s onKey), so the arrows here are only a target for a finger and
 * deliberately cannot take focus: the D-pad must never land on them.
 */
@Composable
internal fun ComboPad(
    entered: List<Dir>,
    length: Int,
    error: String?,
    onTap: (Dir) -> Unit,
    onConfirm: () -> Unit,
    /** False when the caller already draws its own title and hint (the settings gate does). */
    showHeader: Boolean = true,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (showHeader) {
            BasicText(stringResource(R.string.kid_combo_title), style = Type.LargeTitle)
            Spacer(Modifier.height(6.dp))
            BasicText(
                error ?: stringResource(R.string.kid_combo_hint),
                style = Type.Caption.copy(color = if (error != null) Palette.Red else Palette.SecondaryLabel, textAlign = TextAlign.Center),
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(length) { i ->
                val dir = entered.getOrNull(i)
                Box(
                    Modifier.size(34.dp).clip(CircleShape)
                        .background(if (dir != null) Palette.FillStrong else Color.Transparent)
                        .border(1.5.dp, if (dir != null) Palette.Focus else Palette.TertiaryLabel, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { if (dir != null) Arrow(dir, Palette.Label, 14.dp) }
            }
        }
        Spacer(Modifier.height(16.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ArrowKey(Dir.Up, onTap)
            Row(horizontalArrangement = Arrangement.spacedBy(46.dp), verticalAlignment = Alignment.CenterVertically) {
                ArrowKey(Dir.Left, onTap)
                ArrowKey(Dir.Right, onTap)
            }
            ArrowKey(Dir.Down, onTap)
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(Palette.FillStrong)
                .height(40.dp)
                .padding(horizontal = 30.dp)
                .pointerInput(onConfirm) { detectTapGestures { onConfirm() } },
            contentAlignment = Alignment.Center,
        ) { BasicText(stringResource(R.string.kid_combo_ok), style = Type.Body.copy(color = Palette.Label)) }
    }
}

/** A drawn arrow, so no font has to carry the glyphs. */
@Composable
private fun Arrow(dir: Dir, color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            when (dir) {
                Dir.Up -> { moveTo(w / 2, 0f); lineTo(w, h); lineTo(0f, h) }
                Dir.Down -> { moveTo(w / 2, h); lineTo(w, 0f); lineTo(0f, 0f) }
                Dir.Left -> { moveTo(0f, h / 2); lineTo(w, 0f); lineTo(w, h) }
                Dir.Right -> { moveTo(w, h / 2); lineTo(0f, 0f); lineTo(0f, h) }
            }
            close()
        }
        drawPath(path, color)
    }
}

@Composable
private fun ArrowKey(dir: Dir, onTap: (Dir) -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.Fill)
            .pointerInput(dir) { detectTapGestures { onTap(dir) } },
        contentAlignment = Alignment.Center,
    ) { Arrow(dir, Palette.Label, 16.dp) }
}

// ---- The admin password ----

private val KEY_ROWS = listOf("1234567890", "QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM")

/**
 * Eight characters, letters and digits, typed on screen (most remotes have no letter keys) or with
 * the remote's own keys where it has them — [ParentalWallHost]'s onKey takes those at the root.
 */
@Composable
internal fun AdminPad(
    entered: String,
    wrong: Boolean,
    message: String,
    onType: (Char) -> Unit,
    onDelete: () -> Unit,
    onSubmit: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText(stringResource(R.string.kid_admin_title), style = Type.LargeTitle)
        Spacer(Modifier.height(6.dp))
        BasicText(
            message,
            style = Type.Caption.copy(
                color = if (wrong) Palette.Red else Palette.SecondaryLabel,
                textAlign = TextAlign.Center,
            ),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(Parental.ADMIN_LENGTH) { i ->
                val ch = entered.getOrNull(i)
                Box(
                    Modifier.size(30.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (ch != null) Palette.FillStrong else Color.Transparent)
                        .border(1.5.dp, if (ch != null) Palette.Focus else Palette.TertiaryLabel, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) { BasicText(ch?.toString().orEmpty(), style = Type.Headline.copy(color = Palette.Label)) }
            }
        }
        Spacer(Modifier.height(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            KEY_ROWS.forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    row.forEachIndexed { c, ch ->
                        KeyCap(
                            label = ch.toString(),
                            focusRequester = if (r == 0 && c == 0) first else null,
                            onClick = { onType(ch) },
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                KeyCap(label = stringResource(R.string.kid_admin_delete), wide = true, onClick = onDelete)
                KeyCap(
                    label = stringResource(R.string.kid_admin_ok),
                    wide = true,
                    enabled = entered.length == Parental.ADMIN_LENGTH,
                    onClick = onSubmit,
                )
            }
        }
    }
}

@Composable
private fun KeyCap(
    label: String,
    wide: Boolean = false,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    Pressable(onClick = onClick, enabled = enabled, focusRequester = focusRequester, focusedScale = 1.08f) { state ->
        val bg by animateColorAsState(
            when {
                state.focused -> Palette.Focus
                enabled -> Palette.Fill
                else -> Color.White.copy(alpha = 0.05f)
            },
            tween(120, easing = Motion.EaseOut), label = "keycap",
        )
        val fg = when {
            state.focused -> Palette.OnFocus
            enabled -> Palette.Label
            else -> Palette.TertiaryLabel
        }
        Box(
            Modifier.size(if (wide) 150.dp else 42.dp, 40.dp).clip(RoundedCornerShape(10.dp)).background(bg),
            contentAlignment = Alignment.Center,
        ) { BasicText(label, style = Type.Body.copy(color = fg)) }
    }
}

// ---- The maths question ----

private class Question(val a: Int, val b: Int, val op: Char, val answer: Int, val options: List<Int>) {
    val text: String get() = "$a $op $b = ?"
}

private fun newQuestion(random: Random): Question {
    val op = listOf('+', '-', '×').random(random)
    val a: Int
    val b: Int
    when (op) {
        '+' -> { a = (11..89).random(random); b = (11..49).random(random) }
        '-' -> { a = (41..99).random(random); b = (10..(a - 10)).random(random) }
        else -> { a = (3..9).random(random); b = (3..9).random(random) }
    }
    val answer = when (op) {
        '+' -> a + b
        '-' -> a - b
        else -> a * b
    }
    val options = linkedSetOf(answer)
    while (options.size < 3) {
        val candidate = answer + (1..9).random(random) * if (random.nextBoolean()) 1 else -1
        if (candidate > 0) options += candidate
    }
    return Question(a, b, op, answer, options.shuffled(random))
}

/** Three answers to pick from with the D-pad; a wrong one swaps in a new question. */
@Composable
private fun MathPad(
    wrong: Boolean,
    firstAction: FocusRequester,
    onWrong: () -> Unit,
    onRight: () -> Unit,
) {
    var question by remember { mutableStateOf(newQuestion(Random.Default)) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText(stringResource(R.string.kid_math_title), style = Type.LargeTitle)
        Spacer(Modifier.height(6.dp))
        BasicText(
            if (wrong) stringResource(R.string.kid_math_wrong) else stringResource(R.string.kid_math_hint),
            style = Type.Caption.copy(color = if (wrong) Palette.Red else Palette.SecondaryLabel),
        )
        Spacer(Modifier.height(16.dp))
        BasicText(question.text, style = Type.Clock.copy(fontSize = 38.sp, lineHeight = 42.sp))
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            question.options.forEachIndexed { i, value ->
                WallButton(
                    label = value.toString(),
                    focusRequester = if (i == 0) firstAction else null,
                    onClick = {
                        if (value == question.answer) {
                            onRight()
                        } else {
                            onWrong()
                            question = newQuestion(Random.Default)
                        }
                    },
                )
            }
        }
    }
}

// ---- A parent's minutes ----

/** The five fixed choices the owner asked for, in a row that scrolls if it has to. */
@Composable
private fun PassPicker(onPick: (Int) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    BasicText(stringResource(R.string.kid_wall_pick_minutes), style = Type.LargeTitle)
    Spacer(Modifier.height(10.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(listOf(10, 20, 30, 40, 50)) { minutes ->
            WallButton(
                label = stringResource(R.string.kid_minutes, minutes),
                focusRequester = if (minutes == 10) first else null,
                onClick = { onPick(minutes) },
            )
        }
    }
}
