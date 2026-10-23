package app.orchard.tv.ui.parental

import android.content.Intent
import android.provider.Settings
import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.orchard.tv.R
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.guard.GuardSetup
import app.orchard.tv.parental.DayRule
import app.orchard.tv.parental.Dir
import app.orchard.tv.parental.Group
import app.orchard.tv.parental.Parental
import app.orchard.tv.parental.Usage
import app.orchard.tv.parental.WatchService
import app.orchard.tv.parental.Window
import app.orchard.tv.parental.clock
import app.orchard.tv.parental.weekdayIndex
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 儿童与时间管理: the two passwords, the locked apps, and the time-limit groups, on the TV with the
 * remote (docs/PARENTAL.md). One admin entry keeps the screen open for ten minutes, so a parent is
 * not asked again at every row.
 */
@Composable
fun KidSettingsSheet(visible: Boolean, apps: List<AppEntry>, onDismiss: () -> Unit) {
    // Held above the animation, so that closing the sheet always starts the next visit at the top
    // rather than on whatever screen (or half-made group) the last one stopped at.
    var screen by remember { mutableStateOf<Screen>(Screen.Root) }
    var gate by remember { mutableStateOf<Gate?>(null) }
    /** A new group that is not saved yet: nothing is kept unless the parent presses Done. */
    var draft by remember { mutableStateOf<Group?>(null) }
    val defaultGroupName = stringResource(R.string.kid_group_default)
    LaunchedEffect(visible) {
        // Every visit starts at the top: no gate, no screen and no half-made group left over from
        // the last time (that leftover was one way Back could land on the group screen).
        if (visible) {
            screen = Screen.Root
            gate = null
            draft = null
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(140)),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.65f)), contentAlignment = Alignment.Center) {
            // Back belongs to the gate while one is up: closing the password prompt must not also
            // close (or leave) the sheet behind it, which is how a cancel used to land on the
            // group screen.
            BackHandler(enabled = gate == null) {
                when (screen) {
                    Screen.Root -> onDismiss()
                    else -> {
                        screen = Screen.Root
                        draft = null
                    }
                }
            }

            Column(
                Modifier
                    .width(880.dp)
                    .heightIn(max = 500.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(Palette.Glass)
                    .border(1.dp, Palette.Hairline, RoundedCornerShape(26.dp))
                    .padding(horizontal = 30.dp, vertical = 22.dp),
            ) {
                BasicText(stringResource(R.string.kid_settings_title), style = Type.LargeTitle)
                Spacer(Modifier.height(14.dp))
                when (val s = screen) {
                    Screen.Root -> Root(
                        onPickLocked = { screen = Screen.Apps(null) },
                        onNewGroup = {
                            // Not stored yet: the group lives on this screen only, and Done is
                            // what adds it — Back throws it away.
                            val created = Group(id = Parental.newGroupId(), name = defaultGroupName, packages = emptySet())
                            draft = created
                            screen = Screen.EditGroup(created.id, fresh = true)
                        },
                        onEdit = {
                            draft = null
                            screen = Screen.EditGroup(it)
                        },
                        onSetAdmin = { gate = Gate.CreateAdmin },
                        onSetAppCombo = { gate = Gate.CreateAppCombo },
                        onSetKidCombo = { gate = Gate.CreateKidCombo },
                    )

                    is Screen.Apps -> {
                        val fresh = s.fresh
                        val owner = when {
                            s.groupId == null -> null
                            fresh -> draft
                            else -> Parental.groups.firstOrNull { it.id == s.groupId }
                        }
                        AppPicker(
                            apps = apps,
                            title = stringResource(if (s.groupId == null) R.string.kid_pick_locked else R.string.kid_pick_apps),
                            chosen = if (s.groupId == null) Parental.lockedPackages else owner?.packages.orEmpty(),
                            onToggle = { pkg ->
                                if (s.groupId == null) {
                                    val locked = Parental.lockedPackages
                                    Parental.chooseLocked(if (pkg in locked) locked - pkg else locked + pkg)
                                } else {
                                    val group = owner
                                    if (group != null) {
                                        val next = if (pkg in group.packages) group.packages - pkg else group.packages + pkg
                                        val edited = group.copy(packages = next)
                                        if (fresh) draft = edited else Parental.saveGroup(edited)
                                    }
                                }
                            },
                            onBack = { screen = if (s.groupId == null) Screen.Root else Screen.EditGroup(s.groupId, fresh) },
                        )
                    }

                    is Screen.EditGroup -> {
                        val group = if (s.fresh) draft else Parental.groups.firstOrNull { it.id == s.id }
                        if (group == null) {
                            LaunchedEffect(s.id) { screen = Screen.Root }
                        } else {
                            GroupEditor(
                                group = group,
                                fresh = s.fresh,
                                onChange = { if (s.fresh) draft = it else Parental.saveGroup(it) },
                                onSave = {
                                    Parental.saveGroup(group)
                                    draft = null
                                    screen = Screen.Root
                                },
                                onPickApps = { screen = Screen.Apps(group.id, s.fresh) },
                                onBack = {
                                    draft = null
                                    screen = Screen.Root
                                },
                                onDeleted = {
                                    draft = null
                                    screen = Screen.Root
                                },
                            )
                        }
                    }
                }
            }

            gate?.let { g ->
                GateOverlay(g) { gate = null }
            }
        }
    }
}

private sealed interface Screen {
    data object Root : Screen
    /**
     * [groupId] null picks the locked apps; otherwise the group's own apps. [fresh] marks a group
     * that has not been saved yet, so the picks go to the draft instead of the store.
     */
    data class Apps(val groupId: String?, val fresh: Boolean = false) : Screen
    data class EditGroup(val id: String, val fresh: Boolean = false) : Screen
}

/**
 * The panels this sheet puts up. Only the ones that *make* a password: checking it happens once, on
 * the way into the sheet (see HomeViewModel.whenKidUnlocked), so no row asks again.
 */
private sealed interface Gate {
    data object CreateAdmin : Gate
    data object CreateAppCombo : Gate
    data object CreateKidCombo : Gate
}

@Composable
private fun GateOverlay(gate: Gate, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center,
    ) {
        when (gate) {
            Gate.CreateAdmin -> AdminGate(
                title = stringResource(R.string.kid_admin_new),
                subtitle = stringResource(R.string.kid_admin_hint),
                askTwice = true,
                onDone = onClose,
                onCancel = onClose,
            )

            Gate.CreateAppCombo -> ComboSetup(ComboKind.App, onDone = { onClose() }, onCancel = onClose)

            Gate.CreateKidCombo -> ComboSetup(ComboKind.Kid, onDone = { onClose() }, onCancel = onClose)
        }
    }
}

// ---- Root ----

@Composable
private fun ColumnScope.Root(
    onPickLocked: () -> Unit,
    onNewGroup: () -> Unit,
    onEdit: (String) -> Unit,
    onSetAdmin: () -> Unit,
    onSetAppCombo: () -> Unit,
    onSetKidCombo: () -> Unit,
) {
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    // Plain local state, not remembered: which control is "first" is decided afresh each pass.
    var taken = false
    fun next(): FocusRequester? = if (taken) null else { taken = true; first }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Card {
            RowItem(stringResource(R.string.kid_master), null, first = true) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Chip(stringResource(R.string.kid_on), Parental.enabled, next()) {
                        // Turning it on needs an admin password to exist: the rules it enforces are
                        // only worth anything if they cannot be switched off without one.
                        if (!Parental.enabled) {
                            if (Parental.adminSet) {
                                Parental.chooseEnabled(true)
                                // Aerial already holds WRITE_SECURE_SETTINGS from the installer, so the
                                // watch can be switched on here instead of sending the owner to Settings.
                                GuardSetup.ensureWatch(context)
                            } else {
                                onSetAdmin()
                            }
                        }
                    }
                    Chip(stringResource(R.string.kid_off), !Parental.enabled, next()) {
                        if (Parental.enabled) {
                            Parental.chooseEnabled(false)
                            GuardSetup.releaseWatch(context)
                        }
                    }
                }
            }
            RowItem(stringResource(R.string.kid_service), stringResource(R.string.kid_service_hint)) {
                val serviceOn = WatchService.running || WatchService.enabledInSettings(context)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText(
                        stringResource(if (serviceOn) R.string.kid_service_on else R.string.kid_service_off),
                        style = Type.Body.copy(color = if (serviceOn) Palette.Green else Palette.Orange),
                    )
                    Chip(stringResource(R.string.kid_service_open), false, next()) {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                }
            }
            RowItem(stringResource(R.string.kid_admin), null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BasicText(
                        stringResource(if (Parental.adminSet) R.string.kid_admin_set else R.string.kid_admin_none),
                        style = Type.Body.copy(color = Palette.SecondaryLabel),
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    Chip(stringResource(if (Parental.adminSet) R.string.kid_admin_change else R.string.kid_admin_new), false, next(), onClick = onSetAdmin)
                    if (Parental.adminSet) {
                        Chip(stringResource(R.string.kid_clear), false, next()) {
                            Parental.clearAdmin()
                            Parental.chooseEnabled(false)
                            GuardSetup.releaseWatch(context)
                        }
                    }
                }
            }
            // Both password rows work whenever the sheet is open: the admin password is the gate,
            // and a row whose chips cannot even be focused is how the owner could not set them at
            // all (the old "turn Children's Mode on first" rule skipped these rows on the D-pad).
            RowItem(stringResource(R.string.kid_app_combo), stringResource(R.string.kid_app_combo_hint)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText(
                        if (Parental.appComboSet) stringResource(R.string.kid_combo_set, Parental.appComboLength)
                        else stringResource(R.string.kid_combo_none),
                        style = Type.Body.copy(color = Palette.SecondaryLabel),
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    Chip(
                        stringResource(if (Parental.appComboSet) R.string.kid_admin_change else R.string.kid_admin_new),
                        false, next(),
                    ) { onSetAppCombo() }
                    if (Parental.appComboSet) {
                        Chip(stringResource(R.string.kid_clear), false, next()) {
                            Parental.clearAppCombo()
                        }
                    }
                }
            }
            RowItem(stringResource(R.string.kid_kid_combo), stringResource(R.string.kid_kid_combo_hint)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText(
                        when {
                            !Parental.kidComboSame && Parental.kidComboSet -> stringResource(R.string.kid_combo_set, Parental.kidComboLengthShown)
                            Parental.appComboSet -> stringResource(R.string.kid_combo_same)
                            else -> stringResource(R.string.kid_combo_none)
                        },
                        style = Type.Body.copy(color = Palette.SecondaryLabel),
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    Chip(
                        stringResource(if (Parental.kidComboSet && !Parental.kidComboSame) R.string.kid_admin_change else R.string.kid_admin_new),
                        false, next(),
                    ) { onSetKidCombo() }
                    if (!Parental.kidComboSame && Parental.kidComboSet) {
                        Chip(stringResource(R.string.kid_combo_use_app), false, next()) {
                            Parental.chooseKidComboSame(true)
                        }
                    }
                }
            }
            RowItem(stringResource(R.string.kid_age), stringResource(R.string.kid_age_hint)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    StepChip("−", next()) { Parental.setAge(Parental.childAge - 1) }
                    BasicText(
                        stringResource(R.string.kid_age_value, Parental.childAge),
                        style = Type.Body.copy(textAlign = TextAlign.Center),
                        modifier = Modifier.width(64.dp),
                    )
                    StepChip("+", next()) { Parental.setAge(Parental.childAge + 1) }
                }
            }
            RowItem(stringResource(R.string.kid_locked), stringResource(R.string.kid_locked_hint)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BasicText(
                        stringResource(R.string.kid_apps_count, Parental.lockedPackages.size),
                        style = Type.Body.copy(color = Palette.SecondaryLabel),
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    Chip(stringResource(R.string.kid_choose), false, next(), onClick = onPickLocked)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Card {
            RowItem(stringResource(R.string.kid_groups), stringResource(R.string.kid_groups_hint), first = true) {
                Chip(stringResource(R.string.kid_group_new), false, next(), onClick = onNewGroup)
            }
            val now = System.currentTimeMillis()
            Parental.groups.forEach { group ->
                RowItem(group.name, null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        val rule = group.dayRule(now)
                        BasicText(
                            stringResource(
                                R.string.kid_group_summary,
                                group.packages.size,
                                Usage.usedMinutes(group.id),
                                label(rule.dailyMinutes),
                            ),
                            style = Type.Callout.copy(color = Palette.SecondaryLabel),
                            modifier = Modifier.padding(end = 4.dp),
                        )
                        val pass = Usage.passLeftSec(group.id)
                        if (pass > 0) {
                            BasicText(
                                stringResource(R.string.kid_pass_left, (pass + 59) / 60),
                                style = Type.Callout.copy(color = Palette.Orange),
                                modifier = Modifier.padding(end = 4.dp),
                            )
                        }
                        Chip(stringResource(R.string.kid_edit), false, next()) { onEdit(group.id) }
                        Chip(stringResource(R.string.kid_reset), false, next()) { Usage.resetToday(group.id) }
                        Chip(stringResource(R.string.kid_clear), false, next()) { Parental.removeGroup(group.id) }
                    }
                }
            }
            if (Parental.groups.isEmpty()) {
                RowItem(stringResource(R.string.kid_groups_none), null) {
                    BasicText("—", style = Type.Body.copy(color = Palette.TertiaryLabel))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        BasicText(
            stringResource(R.string.kid_root_hint),
            style = Type.Hint.copy(color = Palette.TertiaryLabel),
            modifier = Modifier.padding(start = 12.dp),
        )
        Spacer(Modifier.height(8.dp))
    }
}

// ---- Apps ----

@Composable
private fun AppPicker(
    apps: List<AppEntry>,
    title: String,
    chosen: Set<String>,
    onToggle: (String) -> Unit,
    onBack: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }

    BasicText(title, style = Type.Headline)
    Spacer(Modifier.height(10.dp))
    Column(Modifier.verticalScroll(rememberScrollState())) {
        apps.chunked(4).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEachIndexed { i, app ->
                    AppChoice(
                        label = app.label,
                        on = app.packageName in chosen,
                        focusRequester = if (rowIndex == 0 && i == 0) first else null,
                        modifier = Modifier.weight(1f),
                        onClick = { onToggle(app.packageName) },
                    )
                }
                // Keep the last row's cells the same width as the rows above.
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Chip(stringResource(R.string.kid_done), true, null, onClick = onBack)
        BasicText(
            stringResource(R.string.kid_pick_hint),
            style = Type.Hint.copy(color = Palette.TertiaryLabel),
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun AppChoice(
    label: String,
    on: Boolean,
    focusRequester: FocusRequester?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Pressable(onClick = onClick, focusRequester = focusRequester, modifier = modifier, focusedScale = 1.04f) { state ->
        val bg by animateColorAsState(
            when {
                state.focused -> Palette.Focus
                on -> Palette.FillStrong
                else -> Palette.Fill
            },
            tween(120, easing = Motion.EaseOut), label = "app-choice",
        )
        val fg = if (state.focused) Palette.OnFocus else Palette.Label
        Row(
            Modifier.clip(RoundedCornerShape(12.dp)).background(bg).height(42.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicText(
                label,
                style = Type.Body.copy(color = fg),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (on) BasicText("✓", style = Type.Body.copy(color = fg))
        }
    }
}

// ---- One group ----

private val DAILY_CHOICES = listOf(30, 60, 90, 120, 180, 240, 0)
private val SESSION_CHOICES = listOf(15, 20, 30, 45, 60, 90, 0)
private val BREAK_CHOICES = listOf(0, 5, 10, 15, 20, 30, 60)

@Composable
private fun GroupEditor(
    group: Group,
    fresh: Boolean,
    onChange: (Group) -> Unit,
    onSave: () -> Unit,
    onPickApps: () -> Unit,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
) {
    // Which weekdays the knobs below apply to. Several at once, as the owner asked: tap Mon and
    // Tue and the next change lands on both.
    var sel by remember(group.id) { mutableStateOf(setOf(weekdayIndex(System.currentTimeMillis()))) }
    val primary = sel.minOrNull() ?: 0
    val names = stringArrayResource(R.array.kid_day_names)
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    // Plain local state, not remembered: which control is "first" is decided afresh each pass.
    var taken = false
    fun next(): FocusRequester? = if (taken) null else { taken = true; first }

    /** Applies one change to every selected day. */
    fun editSel(change: (DayRule) -> DayRule) {
        val days = group.days.toMutableList()
        val targets = sel.ifEmpty { setOf(primary) }
        targets.forEach { i -> if (i in days.indices) days[i] = change(days[i]) }
        onChange(group.copy(days = days))
    }

    /** True when every selected day agrees on [rule] — a chip then shows as chosen. */
    fun allAgree(rule: (DayRule) -> Boolean): Boolean =
        sel.all { i -> group.days.getOrNull(i)?.let(rule) == true }

    BasicText(group.name, style = Type.LargeTitle)
    if (fresh) {
        Spacer(Modifier.height(4.dp))
        BasicText(
            stringResource(R.string.kid_group_unsaved),
            style = Type.Hint.copy(color = Palette.Orange),
        )
    }
    Spacer(Modifier.height(8.dp))
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Card {
            RowItem(stringResource(R.string.kid_group_name), null, first = true) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    stringArrayResource(R.array.kid_group_names).forEach { name ->
                        Chip(name, group.name == name, next()) { onChange(group.copy(name = name)) }
                    }
                }
            }
            RowItem(stringResource(R.string.kid_group_apps), stringResource(R.string.kid_group_apps_hint)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BasicText(
                        stringResource(R.string.kid_apps_count, group.packages.size),
                        style = Type.Body.copy(color = Palette.SecondaryLabel),
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    Chip(stringResource(R.string.kid_choose), false, next(), onClick = onPickApps)
                }
            }
            RowItem(stringResource(R.string.kid_group_enabled), null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Chip(stringResource(R.string.kid_on), allAgree { it.enabled }, next()) {
                        editSel { it.copy(enabled = true) }
                    }
                    Chip(stringResource(R.string.kid_off), allAgree { !it.enabled }, next()) {
                        editSel { it.copy(enabled = false) }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        BasicText(stringResource(R.string.kid_group_days), style = Type.Callout)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            names.forEachIndexed { i, name ->
                Chip(name, i in sel, next()) {
                    // A multi-select: tap to add or drop a day. One day always stays, or the knobs
                    // below would have nothing to edit.
                    sel = when {
                        i !in sel -> sel + i
                        sel.size > 1 -> sel - i
                        else -> sel
                    }
                }
            }
            Chip(stringResource(R.string.kid_group_same_all), sel.size == names.size, next()) {
                // One rule for the whole week: today's (or the first chosen day's) rule is copied to
                // all seven and all seven stay selected.
                val rule = group.days[primary]
                sel = (0 until names.size).toSet()
                onChange(group.copy(days = List(names.size) { rule }))
            }
        }

        Spacer(Modifier.height(10.dp))
        Card {
            RowItem(stringResource(R.string.kid_group_daily), stringResource(R.string.kid_group_daily_hint), first = true) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DAILY_CHOICES.forEach { minutes ->
                        Chip(label(minutes), allAgree { it.dailyMinutes == minutes }, next()) {
                            editSel { it.copy(dailyMinutes = minutes) }
                        }
                    }
                }
            }
            RowItem(stringResource(R.string.kid_group_session), stringResource(R.string.kid_group_session_hint)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SESSION_CHOICES.forEach { minutes ->
                        Chip(label(minutes), allAgree { it.sessionMinutes == minutes }, next()) {
                            editSel { it.copy(sessionMinutes = minutes) }
                        }
                    }
                }
            }
            RowItem(stringResource(R.string.kid_group_break), stringResource(R.string.kid_group_break_hint)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BREAK_CHOICES.forEach { minutes ->
                        Chip(label(minutes), allAgree { it.breakMinutes == minutes }, next()) {
                            editSel { it.copy(breakMinutes = minutes) }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Card {
            RowItem(stringResource(R.string.kid_group_windows), stringResource(R.string.kid_group_windows_hint), first = true) {
                Chip(stringResource(R.string.kid_group_window_add), false, next()) {
                    val end = group.days[primary].windows.maxOfOrNull { it.end } ?: (18 * 60)
                    val start = (end + 30).coerceAtMost(23 * 60)
                    editSel { it.copy(windows = it.windows + Window(start, (start + 60).coerceAtMost(24 * 60))) }
                }
            }
            val windows = group.days[primary].windows
            if (windows.isEmpty()) {
                RowItem(stringResource(R.string.kid_group_window_none), null) {
                    BasicText("—", style = Type.Body.copy(color = Palette.TertiaryLabel))
                }
            }
            windows.forEachIndexed { index, window ->
                RowItem(stringResource(R.string.kid_group_window_n, index + 1), null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        StepChip("−", next()) { editSel { d -> d.copy(windows = d.windows.replace(index, window.copy(start = (window.start - 30).coerceAtLeast(0)))) } }
                        BasicText(clock(window.start), style = Type.Body)
                        StepChip("+", next()) { editSel { d -> d.copy(windows = d.windows.replace(index, window.copy(start = (window.start + 30).coerceIn(0, window.end - 30)))) } }
                        Spacer(Modifier.width(6.dp))
                        BasicText("–", style = Type.Body.copy(color = Palette.TertiaryLabel))
                        Spacer(Modifier.width(6.dp))
                        StepChip("−", next()) { editSel { d -> d.copy(windows = d.windows.replace(index, window.copy(end = (window.end - 30).coerceAtLeast(window.start + 30)))) } }
                        BasicText(clock(window.end), style = Type.Body)
                        StepChip("+", next()) { editSel { d -> d.copy(windows = d.windows.replace(index, window.copy(end = (window.end + 30).coerceAtMost(24 * 60)))) } }
                        Chip(stringResource(R.string.kid_clear), false, next()) {
                            editSel { d -> d.copy(windows = d.windows.filterIndexed { i, _ -> i != index }) }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (fresh) {
                // Nothing is in the store yet, so Done is the only thing that adds the group and
                // Cancel drops it. Back does the same as Cancel (see the sheet's BackHandler).
                Chip(stringResource(R.string.kid_done), true, next(), onClick = onSave)
                Chip(stringResource(R.string.kid_cancel), false, next(), onClick = onBack)
            } else {
                Chip(stringResource(R.string.kid_back), true, next(), onClick = onBack)
                Chip(stringResource(R.string.kid_group_delete), false, next()) {
                    Parental.removeGroup(group.id)
                    onDeleted()
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

/** "30 minutes" / "3 hours" / "No limit" on one chip. */
@Composable
private fun label(minutes: Int): String = when {
    minutes == 0 -> stringResource(R.string.kid_no_limit)
    minutes % 60 == 0 -> stringResource(R.string.kid_hours, minutes / 60)
    else -> stringResource(R.string.kid_minutes, minutes)
}

private fun List<Window>.replace(index: Int, window: Window): List<Window> =
    toMutableList().also { if (index in indices) it[index] = window }

// ---- Small controls ----

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.05f)),
        content = content,
    )
}

@Composable
private fun RowItem(label: String, hint: String?, first: Boolean = false, control: @Composable () -> Unit) {
    if (!first) Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(1.dp).background(Palette.Hairline))
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            BasicText(label, style = Type.Headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (hint != null) {
                // Two lines are allowed: at a readable size a hint is worth more than one line.
                BasicText(hint, style = Type.Hint.copy(color = Palette.TertiaryLabel), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        control()
    }
}

@Composable
private fun Chip(
    label: String,
    on: Boolean,
    focusRequester: FocusRequester?,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Pressable(onClick = onClick, enabled = enabled, focusRequester = focusRequester, focusedScale = 1.06f) { state ->
        val bg by animateColorAsState(
            when {
                state.focused -> Palette.Focus
                on -> Palette.FillStrong
                else -> Palette.Fill
            },
            tween(120, easing = Motion.EaseOut), label = "kid-chip",
        )
        val fg = if (state.focused) Palette.OnFocus else Palette.Label
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(bg).height(36.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (on) BasicText("✓", style = Type.Callout.copy(color = fg))
            BasicText(label, style = Type.Callout.copy(color = fg))
        }
    }
}

@Composable
private fun StepChip(label: String, focusRequester: FocusRequester?, onClick: () -> Unit) {
    Pressable(onClick = onClick, focusRequester = focusRequester, focusedScale = 1.1f) { state ->
        val bg by animateColorAsState(if (state.focused) Palette.Focus else Palette.Fill, tween(120), label = "step")
        val fg = if (state.focused) Palette.OnFocus else Palette.Label
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(bg),
            contentAlignment = Alignment.Center,
        ) { BasicText(label, style = Type.Body.copy(color = fg)) }
    }
}

// ---- Password dialogs ----

/**
 * The parent's password on its own, for the screens it guards: Aerial's TV settings, the hidden
 * apps, and the parental sheet itself. Drawn above everything, like the block wall.
 */
@Composable
fun AdminPrompt(visible: Boolean, onDone: () -> Unit, onCancel: () -> Unit) {
    BackHandler(enabled = visible, onBack = onCancel)
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(160)),
        exit = fadeOut(tween(120)),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f)), contentAlignment = Alignment.Center) {
            AdminGate(
                title = stringResource(R.string.kid_auth_title),
                subtitle = stringResource(R.string.kid_auth_why),
                askTwice = false,
                onDone = onDone,
                onCancel = onCancel,
            )
        }
    }
}

/**
 * One admin entry. [askTwice] is for setting a new password (typing it twice); otherwise it just
 * checks the current one. PBKDF2 runs off the main thread.
 */
@Composable
private fun AdminGate(
    title: String,
    subtitle: String,
    askTwice: Boolean,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    var entered by remember { mutableStateOf("") }
    var firstTry by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf(subtitle) }
    var wrong by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Strings read outside composition, for the messages the checks produce.
    val againText = stringResource(R.string.kid_admin_again)
    val mismatchText = stringResource(R.string.kid_admin_mismatch)
    val wrongText = stringResource(R.string.kid_admin_wrong)
    val formatText = stringResource(R.string.kid_admin_format)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }

    fun submit() {
        if (busy) return
        val value = entered
        if (value.length != Parental.ADMIN_LENGTH) return
        if (askTwice) {
            val first = firstTry
            when {
                !Parental.adminFormatOk(value) -> {
                    entered = ""
                    wrong = true
                    message = formatText
                }
                first == null -> {
                    firstTry = value
                    entered = ""
                    wrong = false
                    message = againText
                }
                first == value -> {
                    Parental.setAdmin(value)
                    Parental.noteAdminEntry()
                    onDone()
                }
                else -> {
                    firstTry = null
                    entered = ""
                    wrong = true
                    message = mismatchText
                }
            }
            return
        }
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.Default) { Parental.verifyAdmin(value) }
            busy = false
            entered = ""
            if (ok) onDone() else {
                wrong = true
                val wait = Parental.waitMs() / 1000
                message = if (wait > 0) waitMessage(context, wait) else wrongText
            }
        }
    }

    Box(
        Modifier
            .width(700.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Palette.Glass)
            .border(1.dp, Palette.Hairline, RoundedCornerShape(26.dp))
            .padding(horizontal = 30.dp, vertical = 22.dp)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val code = e.nativeKeyEvent.keyCode
                val next = adminEdit(entered, code) ?: return@onPreviewKeyEvent false
                entered = next
                true
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(title, style = Type.LargeTitle)
            Spacer(Modifier.height(6.dp))
            BasicText(
                message,
                style = Type.Notice.copy(color = if (wrong) Palette.Red else Palette.SecondaryLabel, textAlign = TextAlign.Center),
            )
            Spacer(Modifier.height(14.dp))
            AdminPad(
                entered = entered,
                wrong = wrong,
                message = if (firstTry != null) stringResource(R.string.kid_admin_again) else stringResource(R.string.kid_admin_hint_short),
                onType = { entered = typeChar(entered, it) },
                onDelete = { entered = entered.dropLast(1) },
                onSubmit = { submit() },
            )
            Spacer(Modifier.height(12.dp))
            Chip(stringResource(R.string.kid_cancel), false, null, onClick = onCancel)
        }
    }
}

/** Which of the two direction passwords a panel sets. */
enum class ComboKind { App, Kid }

/**
 * Sets a direction password: four to eight directions, OK to finish, then the same again. The app
 * password also carries the reminder that changing it later means opening the children's mode.
 */
@Composable
internal fun ComboSetup(kind: ComboKind, onDone: () -> Unit, onCancel: () -> Unit) {
    var entered by remember { mutableStateOf(emptyList<Dir>()) }
    var firstTry by remember { mutableStateOf<List<Dir>?>(null) }
    val rule = stringResource(R.string.kid_combo_create_hint)
    val appRule = stringResource(R.string.kid_combo_app_rule)
    var message by remember { mutableStateOf(if (kind == ComboKind.App) "$rule\n$appRule" else rule) }
    var wrong by remember { mutableStateOf(false) }
    val againText = stringResource(R.string.kid_combo_again)
    val mismatchText = stringResource(R.string.kid_combo_mismatch)
    val shortText = stringResource(R.string.kid_combo_short)
    val focus = remember { FocusRequester() }
    val minLength = Parental.MIN_COMBO
    // Four to eight, and OK is what ends the sequence: a fixed six slots made a four-direction
    // combo look like a dead OK key, which is the other half of "the combo cannot be set".
    val slots = entered.size.coerceIn(minLength, Parental.MAX_COMBO)
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    val title = stringResource(if (kind == ComboKind.App) R.string.kid_combo_app_title else R.string.kid_combo_kid_title)

    fun confirm() {
        if (entered.size < minLength) {
            wrong = true
            message = shortText
            return
        }
        val first = firstTry
        when {
            first == null -> {
                firstTry = entered
                entered = emptyList()
                wrong = false
                message = againText
            }
            first == entered -> {
                if (kind == ComboKind.App) Parental.setAppCombo(entered) else Parental.setKidCombo(entered)
                onDone()
            }
            else -> {
                firstTry = null
                entered = emptyList()
                wrong = true
                message = mismatchText
            }
        }
    }

    Box(
        Modifier
            .width(640.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Palette.Glass)
            .border(1.dp, Palette.Hairline, RoundedCornerShape(26.dp))
            .padding(horizontal = 30.dp, vertical = 22.dp)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (e.nativeKeyEvent.repeatCount > 0) return@onPreviewKeyEvent false
                val code = e.nativeKeyEvent.keyCode
                if (code == NativeKeyEvent.KEYCODE_DPAD_CENTER || code == NativeKeyEvent.KEYCODE_ENTER) {
                    confirm(); return@onPreviewKeyEvent true
                }
                if (code == NativeKeyEvent.KEYCODE_DEL) {
                    entered = entered.dropLast(1); return@onPreviewKeyEvent true
                }
                val next = comboEdit(entered, code, Parental.MAX_COMBO) ?: return@onPreviewKeyEvent false
                entered = next
                true
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(title, style = Type.LargeTitle)
            Spacer(Modifier.height(6.dp))
            BasicText(
                message,
                style = Type.Notice.copy(color = if (wrong) Palette.Red else Palette.SecondaryLabel, textAlign = TextAlign.Center),
            )
            Spacer(Modifier.height(12.dp))
            ComboPad(
                title = "",
                hint = "",
                entered = entered,
                length = slots,
                error = null,
                onTap = { dir -> entered = if (entered.size < Parental.MAX_COMBO) entered + dir else entered },
                onConfirm = { confirm() },
            )
            Spacer(Modifier.height(12.dp))
            Chip(stringResource(R.string.kid_cancel), false, null, onClick = onCancel)
        }
    }
}

/**
 * First lock on an app with no app password yet: the parent makes one right here (the owner asked
 * for this instead of sending them to Settings), then the app is locked.
 */
@Composable
fun AppLockSetup(visible: Boolean, onCancel: () -> Unit, onLocked: () -> Unit) {
    if (!visible) return
    BackHandler(onBack = onCancel)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        ComboSetup(ComboKind.App, onDone = onLocked, onCancel = onCancel)
    }
}

/** Turning a lock off: the app password, and only that. */
@Composable
fun AppUnlockGate(visible: Boolean, onCancel: () -> Unit, onUnlocked: () -> Unit) {
    if (!visible) return
    BackHandler(onBack = onCancel)
    var entered by remember { mutableStateOf(emptyList<Dir>()) }
    var wrong by remember { mutableStateOf(false) }
    var waitSec by remember { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val length = Parental.appComboLength
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }

    fun submit() {
        if (entered.size != length) return
        val dirs = entered
        scope.launch {
            val ok = withContext(Dispatchers.Default) { Parental.verifyAppCombo(dirs) }
            entered = emptyList()
            if (ok) onUnlocked() else {
                wrong = true
                waitSec = Parental.waitMs() / 1000
            }
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(640.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(Palette.Glass)
                .border(1.dp, Palette.Hairline, RoundedCornerShape(26.dp))
                .padding(horizontal = 30.dp, vertical = 22.dp)
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (e.nativeKeyEvent.repeatCount > 0) return@onPreviewKeyEvent false
                    val code = e.nativeKeyEvent.keyCode
                    if (code == NativeKeyEvent.KEYCODE_DPAD_CENTER || code == NativeKeyEvent.KEYCODE_ENTER) {
                        submit(); return@onPreviewKeyEvent true
                    }
                    if (code == NativeKeyEvent.KEYCODE_DEL) {
                        entered = entered.dropLast(1); return@onPreviewKeyEvent true
                    }
                    val next = comboEdit(entered, code, length) ?: return@onPreviewKeyEvent false
                    entered = next
                    true
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(stringResource(R.string.kid_unlock_title), style = Type.LargeTitle)
                Spacer(Modifier.height(6.dp))
                BasicText(
                    when {
                        waitSec > 0 -> waitMessage(context, waitSec)
                        wrong -> stringResource(R.string.kid_combo_wrong)
                        else -> stringResource(R.string.kid_unlock_hint)
                    },
                    style = Type.Notice.copy(
                        color = if (wrong) Palette.Red else Palette.SecondaryLabel,
                        textAlign = TextAlign.Center,
                    ),
                )
                Spacer(Modifier.height(12.dp))
                ComboPad(
                    title = "",
                    hint = "",
                    entered = entered,
                    length = length,
                    error = null,
                    onTap = { dir -> entered = add(entered, dir, length) },
                    onConfirm = { submit() },
                )
                Spacer(Modifier.height(12.dp))
                Chip(stringResource(R.string.kid_cancel), false, null, onClick = onCancel)
            }
        }
    }
}
