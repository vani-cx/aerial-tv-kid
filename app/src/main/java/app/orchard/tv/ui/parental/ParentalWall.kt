package app.orchard.tv.ui.parental

import android.content.Context
import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
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
import app.orchard.tv.parental.clock
import app.orchard.tv.parental.minuteOfDay
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
 * The block screen: why the app stopped, then the way out — the maths question (15 minutes outside
 * the allowed hours, 5 after a rest), the time password (10…50 minutes), or the app password for a
 * locked app.
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
    var question by remember { mutableStateOf(newQuestion(Parental.childAge)) }
    /** What the child has typed into the maths box so far. */
    var entered by remember { mutableStateOf("") }
    /** Stops the one-second re-check while the "you may watch" note is on screen. */
    var holding by remember { mutableStateOf(false) }
    var noteRes by remember { mutableStateOf(R.string.kid_wall_note_rest) }
    var noteMinutes by remember { mutableStateOf(Parental.REST_QUIZ_MINUTES) }
    val scope = rememberCoroutineScope()
    val rootFocus = remember { FocusRequester() }
    val firstAction = remember { FocusRequester() }

    // The rules move while the wall is up: a rest ends, a pass runs out. One look a second closes
    // the wall by itself when it no longer applies.
    LaunchedEffect(pkg) {
        while (true) {
            val now = Parental.verdict(pkg)
            if (now is Verdict.Allow && !holding) {
                onAllowed()
                return@LaunchedEffect
            }
            verdict = now
            waitSec = Parental.waitMs() / 1000
            delay(1_000)
        }
    }

    // A locked app asks for its password straight away; a spent budget explains itself first.
    LaunchedEffect(pkg) {
        if (lockMode) step = Step.AppCombo
    }

    // The pad, the keypad and the buttons all take focus through this: the step decides who gets it.
    LaunchedEffect(step) {
        withFrameNanos { }
        when (step) {
            Step.AppCombo, Step.TimeCombo -> runCatching { rootFocus.requestFocus() }
            Step.Math, Step.Done -> Unit
            else -> runCatching { firstAction.requestFocus() }
        }
    }

    // The note after a right answer stays a moment, then the app opens.
    LaunchedEffect(step) {
        if (step == Step.Done) {
            delay(2_600)
            onAllowed()
        }
    }

    BackHandler {
        when (step) {
            Step.Ask -> onCancel()
            Step.Done -> Unit
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
            val ok = withContext(Dispatchers.Default) {
                if (step == Step.AppCombo) Parental.verifyAppCombo(dirs) else Parental.verifyKidCombo(dirs)
            }
            combo = emptyList()
            if (ok) {
                wrongCombo = false
                if (step == Step.AppCombo) {
                    Parental.markAppUnlocked(pkg)
                    allowIfPossible()
                } else {
                    step = Step.Pass
                }
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

    fun answerMath(right: Boolean) {
        if (!right) {
            wrongMath = true
            entered = ""
            question = newQuestion(Parental.childAge)
            return
        }
        holding = true
        val quiz = blocked ?: return
        // Outside the hours: wall-clock minutes that override the window. After a rest: five more
        // minutes inside the session, which counts towards the day like everything else.
        if (quiz.reason == Reason.Window) {
            Usage.grantWindowQuiz(quiz.group.id, quiz.quizMinutes)
            noteRes = if (quiz.quizMinutes == Parental.WINDOW_QUIZ_MINUTES) R.string.kid_wall_note_window else R.string.kid_wall_note_rest
        } else {
            Usage.grantRestQuiz(quiz.group.id, quiz.quizMinutes)
            noteRes = R.string.kid_wall_note_rest
        }
        noteMinutes = quiz.quizMinutes
        step = Step.Done
    }

    fun onKey(e: KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown) return false
        val code = e.nativeKeyEvent.keyCode
        when (step) {
            Step.AppCombo, Step.TimeCombo -> {
                if (e.nativeKeyEvent.repeatCount > 0) return false
                val length = if (step == Step.AppCombo) Parental.appComboLength else Parental.kidComboLengthShown
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
            Step.Math -> {
                // Digits and Delete are taken here so the remote's own number keys work; the OK key
                // is deliberately NOT taken: this runs on preview, before the focused key sees the
                // press, so swallowing OK here turned every tap on a digit into "submit the empty
                // box" — the wall showed "wrong" and the digit never landed. The keycap gets it.
                when {
                    code in NativeKeyEvent.KEYCODE_0..NativeKeyEvent.KEYCODE_9 -> {
                        // Three boxes: the largest answer here is 188 (89 + 99).
                        if (entered.length < 3) entered += '0' + (code - NativeKeyEvent.KEYCODE_0)
                    }
                    code == NativeKeyEvent.KEYCODE_DEL -> entered = entered.dropLast(1)
                    else -> return false
                }
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
        Panel(width = if (step == Step.Admin) 700.dp else 660.dp) {
            GlyphIcon(
                when (step) {
                    Step.AppCombo, Step.TimeCombo -> Glyph.Switches
                    Step.Admin -> Glyph.Shield
                    Step.Math -> Glyph.Check
                    Step.Done -> Glyph.Check
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
                    onMath = {
                        wrongMath = false
                        entered = ""
                        question = newQuestion(Parental.childAge)
                        step = Step.Math
                    },
                    onEnter = { wrongCombo = false; combo = emptyList(); step = Step.AppCombo },
                    onParent = {
                        wrongCombo = false
                        wrongAdmin = false
                        password = ""
                        combo = emptyList()
                        step = if (Parental.kidDoorSet) Step.TimeCombo else Step.Admin
                    },
                    onHome = onCancel,
                )

                Step.AppCombo -> ComboPad(
                    title = stringResource(R.string.kid_wall_locked_title),
                    hint = stringResource(R.string.kid_wall_locked_body, appLabel),
                    entered = combo,
                    length = Parental.appComboLength,
                    error = when {
                        waitSec > 0 -> waitMessage(LocalContext.current, waitSec)
                        wrongCombo -> stringResource(R.string.kid_combo_wrong)
                        else -> null
                    },
                    onTap = { dir -> combo = add(combo, dir, Parental.appComboLength) },
                    onConfirm = { if (combo.size == Parental.appComboLength) submitCombo(combo) },
                )

                Step.TimeCombo -> ComboPad(
                    title = stringResource(R.string.kid_wall_time_title),
                    hint = stringResource(R.string.kid_wall_time_hint),
                    entered = combo,
                    length = Parental.kidComboLengthShown,
                    error = when {
                        waitSec > 0 -> waitMessage(LocalContext.current, waitSec)
                        wrongCombo -> stringResource(R.string.kid_combo_wrong)
                        else -> null
                    },
                    onTap = { dir -> combo = add(combo, dir, Parental.kidComboLengthShown) },
                    onConfirm = { if (combo.size == Parental.kidComboLengthShown) submitCombo(combo) },
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
                    question = question,
                    entered = entered,
                    wrong = wrongMath,
                    // Typing again clears the red line: the new question is already up, so the old
                    // verdict underneath it would be about the wrong sum.
                    onDigit = {
                        wrongMath = false
                        if (entered.length < 3) entered += it
                    },
                    onDelete = {
                        wrongMath = false
                        entered = entered.dropLast(1)
                    },
                    onSubmit = { answerMath(entered.toIntOrNull() == question.answer) },
                )

                Step.Done -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BasicText(stringResource(R.string.kid_wall_note_title), style = Type.LargeTitle.copy(textAlign = TextAlign.Center))
                    Spacer(Modifier.height(10.dp))
                    BasicText(
                        stringResource(noteRes, noteMinutes),
                        style = Type.Notice.copy(color = Palette.SecondaryLabel, textAlign = TextAlign.Center),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            BasicText(
                when (step) {
                    Step.Ask -> stringResource(R.string.kid_wall_ask_hint)
                    Step.AppCombo, Step.TimeCombo -> stringResource(R.string.kid_combo_hint)
                    Step.Pass -> stringResource(R.string.kid_wall_pick_minutes_hint)
                    Step.Math -> stringResource(R.string.kid_math_hint)
                    else -> stringResource(R.string.kid_wall_back_hint)
                },
                style = Type.Notice.copy(color = Palette.SecondaryLabel, textAlign = TextAlign.Center),
            )
        }
    }
}

private enum class Step { Ask, AppCombo, TimeCombo, Admin, Pass, Math, Done }

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

/** "07:30" for a rest countdown. */
internal fun restClock(seconds: Int): String = "%02d:%02d".format(seconds / 60, seconds % 60)

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

/** The message and the ways out, for the rule that is in force. */
@Composable
private fun ColumnScope.Ask(
    verdict: Verdict,
    appLabel: String,
    firstAction: FocusRequester,
    onMath: () -> Unit,
    onEnter: () -> Unit,
    onParent: () -> Unit,
    onHome: () -> Unit,
) {
    val blocked = verdict as? Verdict.Blocked
    val locked = verdict is Verdict.Locked
    val reason = blocked?.reason
    val context = LocalContext.current
    val nowMinute = minuteOfDay(System.currentTimeMillis())

    val title: String
    val body: String
    when {
        locked || blocked == null -> {
            title = stringResource(R.string.kid_wall_locked_title)
            body = stringResource(R.string.kid_wall_locked_body, appLabel)
        }
        reason == Reason.Daily -> {
            title = stringResource(R.string.kid_wall_daily_title)
            body = stringResource(R.string.kid_wall_daily_body)
        }
        reason == Reason.Break -> {
            title = stringResource(R.string.kid_wall_break_title)
            body = if (blocked.restSec > 0) {
                stringResource(R.string.kid_wall_break_body, restClock(blocked.restSec))
            } else {
                stringResource(R.string.kid_wall_break_body_no_clock)
            }
        }
        else -> {
            // Outside the allowed hours: which line depends on when the next stretch starts.
            val next = blocked.nextAtMinute
            val tomorrow = next <= nowMinute
            val hour = nowMinute / 60
            title = when {
                tomorrow -> stringResource(R.string.kid_wall_late_title)
                hour in 12..13 -> stringResource(R.string.kid_wall_nap_title)
                hour < 12 -> stringResource(R.string.kid_wall_early_title)
                else -> stringResource(R.string.kid_wall_later_title)
            }
            body = when {
                tomorrow -> stringResource(R.string.kid_wall_late_body, clock(next))
                hour in 12..13 -> stringResource(R.string.kid_wall_nap_body)
                hour < 12 -> stringResource(R.string.kid_wall_early_body)
                else -> stringResource(R.string.kid_wall_later_body, clock(next))
            }
        }
    }

    BasicText(title, style = Type.LargeTitle.copy(textAlign = TextAlign.Center))
    Spacer(Modifier.height(8.dp))
    BasicText(body, style = Type.Notice.copy(color = Palette.SecondaryLabel, textAlign = TextAlign.Center))
    if (reason == Reason.Break && blocked.restSec > 0) {
        Spacer(Modifier.height(10.dp))
        BasicText(
            stringResource(R.string.kid_wall_rest_left, restClock(blocked.restSec)),
            style = Type.Clock.copy(fontSize = 30.sp, lineHeight = 34.sp),
        )
    }

    Spacer(Modifier.height(20.dp))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        var firstTaken = false
        fun nextRequester(): FocusRequester? = if (firstTaken) null else { firstTaken = true; firstAction }

        val quiz = blocked != null && blocked.offerQuiz
        if (quiz) {
            WallButton(
                stringResource(R.string.kid_wall_math),
                primary = true,
                focusRequester = nextRequester(),
                onClick = onMath,
            )
        }
        if (locked) {
            WallButton(
                stringResource(R.string.kid_wall_locked_enter),
                primary = true,
                focusRequester = nextRequester(),
                onClick = onEnter,
            )
        }
        if (blocked != null && (Parental.kidDoorSet || Parental.adminSet)) {
            WallButton(stringResource(R.string.kid_wall_parent), primary = blockedOfferPrimary(blocked), focusRequester = nextRequester(), onClick = onParent)
        }
        // Always the way out: Back does the same thing, but a child should not have to guess.
        WallButton(stringResource(R.string.kid_wall_home), focusRequester = nextRequester(), onClick = onHome)
    }

    if (reason == Reason.Daily) {
        Spacer(Modifier.height(12.dp))
        BasicText(
            stringResource(R.string.kid_wall_daily_hint),
            style = Type.Hint.copy(color = Palette.TertiaryLabel, textAlign = TextAlign.Center),
        )
    }
}

/** The parent's button leads when the child cannot ask for time here. */
private fun blockedOfferPrimary(blocked: Verdict.Blocked): Boolean =
    !blocked.offerQuiz && blocked.reason != Reason.Daily

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
 * The pad for a combo. Every direction key is taken at the wall's root before the focus system sees
 * it (see [ParentalWallHost]'s onKey), so the arrows here are only a target for a finger and
 * deliberately cannot take focus: the D-pad must never land on them.
 *
 * What has been entered is a row of dots, not the directions themselves: anyone standing behind the
 * owner could read a password off the screen otherwise, and the owner asked for that to stop.
 */
@Composable
internal fun ComboPad(
    title: String,
    hint: String,
    entered: List<Dir>,
    length: Int,
    error: String?,
    onTap: (Dir) -> Unit,
    onConfirm: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText(title, style = Type.LargeTitle)
        Spacer(Modifier.height(6.dp))
        BasicText(
            error ?: hint,
            style = Type.Notice.copy(color = if (error != null) Palette.Red else Palette.SecondaryLabel, textAlign = TextAlign.Center),
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(length.coerceAtLeast(1)) { i ->
                val filled = entered.getOrNull(i) != null
                Box(
                    Modifier.size(34.dp).clip(CircleShape)
                        .background(if (filled) Palette.FillStrong else Color.Transparent)
                        .border(1.5.dp, if (filled) Palette.Focus else Palette.TertiaryLabel, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { if (filled) SecretDot() }
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
        ) { BasicText(stringResource(R.string.kid_combo_ok), style = Type.Headline.copy(color = Palette.Label)) }
    }
}

/**
 * What a filled password slot shows: a dot, never the character or the direction. The owner asked
 * for this — the password used to be on screen in clear, so anyone standing behind could read it.
 */
@Composable
private fun SecretDot(size: Dp = 10.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Palette.Label))
}

/** A drawn arrow, so no font has to carry the glyphs. */
@Composable
private fun Arrow(dir: Dir, color: Color, size: Dp) {    Canvas(Modifier.size(size)) {
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
            style = Type.Notice.copy(
                color = if (wrong) Palette.Red else Palette.SecondaryLabel,
                textAlign = TextAlign.Center,
            ),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(Parental.ADMIN_LENGTH) { i ->
                val filled = entered.getOrNull(i) != null
                Box(
                    Modifier.size(30.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (filled) Palette.FillStrong else Color.Transparent)
                        .border(1.5.dp, if (filled) Palette.Focus else Palette.TertiaryLabel, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) { if (filled) SecretDot(9.dp) }
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
                KeyCap(label = stringResource(R.string.kid_admin_delete), width = 150.dp, onClick = onDelete)
                KeyCap(
                    label = stringResource(R.string.kid_admin_ok),
                    width = 150.dp,
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
    width: Dp = 42.dp,
    height: Dp = 40.dp,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    textStyle: TextStyle = Type.Body,
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
            Modifier.size(width, height).clip(RoundedCornerShape(10.dp)).background(bg),
            contentAlignment = Alignment.Center,
        ) { BasicText(label, style = textStyle.copy(color = fg)) }
    }
}

// ---- The maths question ----

internal class Question(val text: String, val answer: Int)

/**
 * One question for the child's age, as the owner asked: the maths gets harder as the child grows.
 * 3–5 counts to ten, 6–7 to twenty, 8–9 adds and subtracts within a hundred and knows the tables,
 * 10–12 divides and takes two steps.
 */
internal fun newQuestion(age: Int, random: Random = Random.Default): Question {
    fun q(text: String, answer: Int) = Question(text, answer)
    return when {
        age <= 5 -> {
            if (random.nextBoolean()) {
                val a = (1..9).random(random)
                val b = (1..(10 - a)).random(random)
                q("$a + $b = ?", a + b)
            } else {
                val a = (2..10).random(random)
                val b = (0..a).random(random)
                q("$a - $b = ?", a - b)
            }
        }
        age <= 7 -> {
            if (random.nextBoolean()) {
                val a = (2..15).random(random)
                val b = (2..(20 - a).coerceAtLeast(2)).random(random)
                q("$a + $b = ?", a + b)
            } else {
                val a = (9..20).random(random)
                val b = (2..a).random(random)
                q("$a - $b = ?", a - b)
            }
        }
        age <= 9 -> {
            when (random.nextInt(3)) {
                0 -> {
                    val a = (12..79).random(random)
                    val b = (5..(99 - a).coerceAtLeast(5)).random(random)
                    q("$a + $b = ?", a + b)
                }
                1 -> {
                    val a = (21..99).random(random)
                    val b = (2..a).random(random)
                    q("$a - $b = ?", a - b)
                }
                else -> {
                    val a = (2..9).random(random)
                    val b = (2..9).random(random)
                    q("$a × $b = ?", a * b)
                }
            }
        }
        else -> {
            when (random.nextInt(3)) {
                0 -> {
                    val a = (12..89).random(random)
                    val b = (12..99).random(random)
                    q("$a + $b = ?", a + b)
                }
                1 -> {
                    val b = (3..9).random(random)
                    val answer = (3..12).random(random)
                    q("${b * answer} ÷ $b = ?", answer)
                }
                else -> {
                    val a = (2..9).random(random)
                    val b = (2..9).random(random)
                    val c = (1..20).random(random)
                    q("$a × $b + $c = ?", a * b + c)
                }
            }
        }
    }
}

/**
 * The question, the answer typed so far and a keypad, three columns wide: 1-9, then Confirm / 0 /
 * Delete in the last row so the pad is one block instead of a narrow grid with a wide strip under
 * it (the owner saw the numbers off to the left). A wrong answer says so, clears the box and puts a
 * new question up, as the owner asked; the remote's number keys work as well as the pad (the host
 * takes those at the root, and only those — the OK key belongs to the keys on screen).
 */
@Composable
private fun MathPad(
    question: Question,
    entered: String,
    wrong: Boolean,
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit,
    onSubmit: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    val keyWidth = 84.dp
    val keyHeight = 44.dp
    val digitStyle = Type.Title.copy(fontSize = 22.sp)
    val actionStyle = Type.Headline.copy(fontSize = 16.sp)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText(stringResource(R.string.kid_math_title), style = Type.LargeTitle)
        Spacer(Modifier.height(6.dp))
        BasicText(
            if (wrong) stringResource(R.string.kid_math_wrong) else stringResource(R.string.kid_math_hint),
            style = Type.Notice.copy(color = if (wrong) Palette.Red else Palette.SecondaryLabel, textAlign = TextAlign.Center),
        )
        Spacer(Modifier.height(10.dp))
        BasicText(question.text, style = Type.Clock.copy(fontSize = 40.sp, lineHeight = 44.sp))
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(3) { i ->
                val ch = entered.getOrNull(i)
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (ch != null) Palette.FillStrong else Color.Transparent)
                        .border(1.5.dp, if (ch != null) Palette.Focus else Palette.TertiaryLabel, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) { BasicText(ch?.toString().orEmpty(), style = Type.Headline.copy(color = Palette.Label)) }
            }
        }
        Spacer(Modifier.height(12.dp))
        // One grid, three equal columns, every row the same width: nothing to look off-centre.
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf("123", "456", "789").forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { ch ->
                        KeyCap(
                            label = ch.toString(),
                            width = keyWidth,
                            height = keyHeight,
                            textStyle = digitStyle,
                            focusRequester = if (ch == '1') first else null,
                            onClick = { onDigit(ch) },
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyCap(
                    label = stringResource(R.string.kid_math_ok),
                    width = keyWidth,
                    height = keyHeight,
                    textStyle = actionStyle,
                    enabled = entered.isNotEmpty(),
                    onClick = onSubmit,
                )
                KeyCap(
                    label = "0",
                    width = keyWidth,
                    height = keyHeight,
                    textStyle = digitStyle,
                    onClick = { onDigit('0') },
                )
                KeyCap(
                    label = stringResource(R.string.kid_admin_delete),
                    width = keyWidth,
                    height = keyHeight,
                    textStyle = actionStyle,
                    onClick = onDelete,
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
