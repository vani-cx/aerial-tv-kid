package app.orchard.tv.ui.home

import android.text.format.DateFormat
import android.graphics.PorterDuff
import android.graphics.Matrix
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.orchard.tv.R
import app.orchard.tv.system.NetKind
import app.orchard.tv.system.NetworkStatus
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.components.frozen
import app.orchard.tv.ui.components.glassEdge
import app.orchard.tv.ui.components.smoothMask
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.GlyphIcon
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import app.orchard.tv.weather.Weather
import app.orchard.tv.weather.WeatherKind
import app.orchard.tv.weather.WeatherNow
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** The current time, ticking on the minute. */
@Composable
fun rememberNow(): State<LocalDateTime> = produceState(LocalDateTime.now()) {
    while (true) {
        value = LocalDateTime.now()
        delay(60_000L - System.currentTimeMillis() % 60_000L + 30L)
    }
}

@Composable
fun timeText(now: LocalDateTime): String {
    val context = LocalContext.current
    // 24-hour unless the owner chose 12-hour. With the setting unset, Android falls back to the
    // locale, and zh_CN means 12-hour, which TV clocks here never show (TCL 65S515D, issue #15).
    val twelve = runCatching { android.provider.Settings.System.getString(context.contentResolver, android.provider.Settings.System.TIME_12_24) }.getOrNull() == "12"
    val pattern = if (twelve) "h:mm" else "H:mm"
    return now.format(DateTimeFormatter.ofPattern(pattern))
}

@Composable
fun dateText(now: LocalDateTime): String {
    val locale = Locale.getDefault()
    // zh's best pattern runs the weekday into the date ("M月d日EEEE"); a space keeps them apart.
    val pattern = remember(locale) {
        DateFormat.getBestDateTimePattern(locale, "MMMMdEEEE")
            .replace(Regex("(?<=[^\\s,，'])EEEE"), " EEEE")
            .replace(Regex("EEEE(?=[^\\s,，'])"), "EEEE ")
    }
    return now.format(DateTimeFormatter.ofPattern(pattern, locale))
}

/**
 * The home background, in two states as on tvOS 26. Focus in the top row: the picture itself, with
 * a plate of Liquid Glass under the row ([plate], in px at scroll 0). Focus down in the grid: the
 * whole screen turns to the picture frosted and darker, so the rows read over anything.
 *
 * Everything follows [scroll] (the grid's scroll, [collapse] being where the grid has fully come
 * in), so the change is exactly as fast as the movement that causes it. Nothing is blurred live:
 * the frosted copy is baked small ahead of time (Images.frost) and darkened here once, and the
 * plate shows the part of that frosted copy that lies under it, stretched over the screen through
 * a shader. At rest a frame is one full-screen draw (plus the plate in the top row); both pictures
 * are drawn only through the middle of the scroll, since each frame that draws both costs a second
 * full-screen fill (Xiaomi MiTV-MFTR0: 90th percentile 20 -> 30 ms when the crossfade ran end to end).
 */
@Composable
fun HomeBackground(
    backdrop: ImageBitmap?,
    frosted: ImageBitmap?,
    plate: Rect?,
    scroll: () -> Float,
    collapse: Float,
    /** How far the aerial wallpaper's film shows through (LiveWallpaper): picture and plate give way to it. */
    cover: () -> Float = { 0f },
) {
    Crossfade(
        targetState = backdrop to frosted,
        animationSpec = tween(450, easing = Motion.EaseOut),
        modifier = Modifier.fillMaxSize(),
        label = "ambient",
    ) { (bmp, frost) ->
        // In "Follow App" mode there is no frosted copy: the app's light is small and soft
        // already, so it makes the glass itself.
        val glassSrc = frost ?: bmp
        val dark = remember(glassSrc) { glassSrc?.let { darkened(it, GRID_SHADE) } }
        val plateSrc = remember(glassSrc) { glassSrc?.let { plateTinted(it) } }
        // The plate, baked for one screen size and resting place; outside state, since it is made
        // while drawing.
        val plateFor = remember(plateSrc) { arrayOfNulls<Pair<Pair<Size, Rect>, ImageBitmap>>(1) }
        Canvas(Modifier.fillMaxSize()) {
            val film = cover()
            // On the top row, the film alone through the window: nothing of Compose's full-screen.
            if (film > 0.99f && scroll() < collapse * 0.3f) return@Canvas
            if (bmp == null || glassSrc == null || dark == null || plateSrc == null) {
                drawRect(Color.Black)
                return@Canvas
            }
            val full = IntSize(size.width.roundToInt(), size.height.roundToInt())
            val p = (scroll() / collapse).coerceIn(0f, 1f)
            // The sharp picture and the grid's frosted one cross only through the middle of the
            // scroll; each end is one full-screen draw.
            val g = ((p - 0.3f) / 0.4f).coerceIn(0f, 1f)
            if (g < 0.99f && film < 0.99f) drawImage(bmp, dstSize = full, filterQuality = FilterQuality.Low, alpha = 1f - film)
            if (g > 0.01f) drawImage(dark, dstSize = full, filterQuality = FilterQuality.Low, alpha = g)

            // The plate rides with its row and dissolves into the frosted background: it fades
            // over the first half of the scroll, swelling a little as if lifting off, and settles
            // back the same way when focus returns to the top row. Kept small on purpose: the
            // whole screen shrinking back into the plate read as too much.
            val fade = (1f - smooth((p / 0.55f).coerceIn(0f, 1f))) * (1f - film)
            if (plate == null || fade < 0.01f) return@Canvas
            val radius = PlateCorner.toPx()
            val grow = PlateSwell.toPx() * (1f - fade)
            val r = plate.translate(0f, -scroll()).inflate(grow)
            // Glass, continuous outline and lit edge baked into one picture at the plate's resting
            // place and size, stretched as it swells and riding up with its row as it fades. The GPU
            // draws one image. Composing them in shaders while drawing (1.27) came out solid white
            // on older GPUs (Xiaomi MiTV-ASTP0, Android 9, Mali-G31, issue #183; a HuiQu projector,
            // #181) and drawn apart they cost 3 ms more a frame than the plain rounded plate.
            val baked = plateFor[0]?.takeIf { it.first == size to plate }?.second
                ?: bakePlate(plateSrc, size, plate, radius, 1.2.dp.toPx()).also { plateFor[0] = (size to plate) to it }
            drawImage(
                baked,
                dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()),
                dstSize = IntSize(r.width.roundToInt(), r.height.roundToInt()),
                alpha = fade,
                filterQuality = FilterQuality.Low,
            )
        }
    }
}

private fun smooth(x: Float) = x * x * (3f - 2f * x)

/** The Liquid Glass plate under the top row: how far it reaches past the tiles, and its corners. */
val PlatePadH = 26.dp
val PlatePadV = 20.dp
private val PlateCorner = 32.dp

/** The grid's background: the frosted picture under this much black. */
private const val GRID_SHADE = 0.55f

/** How much the plate swells as it dissolves into the grid's background. */
private val PlateSwell = 14.dp

/** The plate's tint: the frosted picture a little darker, lifted with white, so it reads as milky glass. */
private val PlateTint = floatArrayOf(
    0.8f, 0f, 0f, 0f, 30f,
    0f, 0.8f, 0f, 0f, 30f,
    0f, 0f, 0.8f, 0f, 30f,
    0f, 0f, 0f, 1f, 0f,
)

/** The glass picture with the plate's tint applied ahead of time, so the plate is one plain draw. */
private fun plateTinted(src: ImageBitmap): ImageBitmap {
    val out = src.asAndroidBitmap().copy(android.graphics.Bitmap.Config.ARGB_8888, true)
    val paint = android.graphics.Paint().apply { colorFilter = android.graphics.ColorMatrixColorFilter(PlateTint) }
    android.graphics.Canvas(out).drawBitmap(src.asAndroidBitmap(), 0f, 0f, paint)
    return out.frozen().asImageBitmap()
}

/** The part of [glass] (stretched over [screen]) under [plate], cut to the plate's outline, with its lit edge. */
private fun bakePlate(glass: ImageBitmap, screen: Size, plate: Rect, radius: Float, rim: Float): ImageBitmap {
    val w = plate.width.roundToInt().coerceAtLeast(1)
    val h = plate.height.roundToInt().coerceAtLeast(1)
    val out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    canvas.drawBitmap(
        glass.asAndroidBitmap(),
        Matrix().apply { setScale(screen.width / glass.width, screen.height / glass.height); postTranslate(-plate.left, -plate.top) },
        android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG),
    )
    canvas.drawBitmap(smoothMask(w, h, radius), 0f, 0f, android.graphics.Paint().apply { xfermode = android.graphics.PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
    canvas.drawBitmap(glassEdge(w, h, radius, rim).asAndroidBitmap(), 0f, 0f, null)
    return out.frozen().asImageBitmap()
}

/** A small picture darkened ahead of time, so the grid's background costs no tint per frame. */
private fun darkened(src: ImageBitmap, alpha: Float): ImageBitmap {
    val out = src.asAndroidBitmap().copy(android.graphics.Bitmap.Config.ARGB_8888, true)
    android.graphics.Canvas(out).drawColor(android.graphics.Color.argb((alpha * 255).roundToInt(), 0, 0, 0))
    return out.asImageBitmap()
}

/**
 * The large clock, as on the lock screen: the date over the time, and under it the weather, the
 * city it is for (issue #59) and today's high and low. Left or right, the three lines share one
 * edge, the tiles' edge. The digits stay proportional: tabular ones leave a narrow "1" short of
 * that edge. A soft shadow keeps them readable on bright photos. The clock screensaver shows the same.
 */
@Composable
fun ShelfClock(now: LocalDateTime, modifier: Modifier = Modifier, alignEnd: Boolean = false) {
    val shadow = Shadow(Color.Black.copy(alpha = 0.28f), Offset(0f, 2f), blurRadius = 18f)
    val secondary = Color.White.copy(alpha = 0.78f)
    Column(modifier, horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        BasicText(dateText(now), style = Type.Title.copy(color = Color.White.copy(alpha = 0.9f), shadow = shadow))
        BasicText(
            timeText(now),
            style = Type.Clock.copy(shadow = shadow),
            modifier = Modifier.trimmed(top = ClockTrimTop, bottom = ClockTrimBottom),
        )
        Weather.now?.let { w ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(glyphFor(w), Color.White, 22.dp, Modifier.padding(end = 8.dp))
                BasicText("${w.celsius}°", style = Type.Title.copy(color = Color.White, shadow = shadow))
                val place = Weather.place?.takeIf { it.length <= 12 }
                val range = if (w.high != null && w.low != null) stringResource(R.string.weather_high_low, w.high, w.low) else null
                val detail = listOfNotNull(place, stringResource(conditionName(w.kind)), range).joinToString("  ·  ")
                BasicText(detail, style = Type.Headline.copy(color = secondary, shadow = shadow), modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}

/**
 * The clock's line box reaches well past its digits (31dp above, 32dp below, measured on the
 * Xiaomi). Trimmed so the date sits 14dp over the digits and the weather 18dp under them.
 */
private val ClockTrimTop = 17.dp
private val ClockTrimBottom = 14.dp

/** Lays the content out [top] and [bottom] shorter than it measures, drawn where it was. */
private fun Modifier.trimmed(top: Dp, bottom: Dp) = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    val t = top.roundToPx()
    layout(p.width, (p.height - t - bottom.roundToPx()).coerceAtLeast(0)) { p.place(0, -t) }
}

private fun conditionName(kind: WeatherKind): Int = when (kind) {
    WeatherKind.Clear -> R.string.weather_clear
    WeatherKind.PartlyCloudy -> R.string.weather_partly_cloudy
    WeatherKind.Cloudy -> R.string.weather_cloudy
    WeatherKind.Fog -> R.string.weather_fog
    WeatherKind.Rain -> R.string.weather_rain
    WeatherKind.Snow -> R.string.weather_snow
    WeatherKind.Thunder -> R.string.weather_thunder
}

/**
 * The small clock: the time and the weather in one quiet line in the top left, level with the status
 * pill, so the wallpaper keeps the rest of the screen. A soft shadow keeps it readable on photos.
 */
@Composable
fun CornerClock(now: LocalDateTime, modifier: Modifier = Modifier) {
    val dim = Color.White.copy(alpha = 0.92f)
    val shadow = Shadow(Color.Black.copy(alpha = 0.5f), blurRadius = 8f)
    Row(modifier.height(34.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(timeText(now), style = Type.Title.copy(color = dim, shadow = shadow))
        Weather.now?.let { w ->
            GlyphIcon(glyphFor(w), dim, 18.dp, Modifier.padding(start = 16.dp, end = 6.dp))
            val place = Weather.place?.takeIf { it.length <= 12 }
            BasicText(listOfNotNull(place, "${w.celsius}°").joinToString(" "), style = Type.Headline.copy(color = dim, shadow = shadow))
        }
    }
}

private fun glyphFor(w: WeatherNow): Glyph = when (w.kind) {
    WeatherKind.Clear -> if (w.day) Glyph.Sun else Glyph.Moon
    WeatherKind.PartlyCloudy -> if (w.day) Glyph.CloudSun else Glyph.Cloud
    WeatherKind.Cloudy -> Glyph.Cloud
    WeatherKind.Fog -> Glyph.CloudFog
    WeatherKind.Rain -> Glyph.CloudRain
    WeatherKind.Snow -> Glyph.CloudSnow
    WeatherKind.Thunder -> Glyph.CloudBolt
}

/**
 * Top right: network and, once the shelf clock has scrolled away, the time. Focusable: it is
 * where Up from the top row leads, and it opens Control Center.
 */
@Composable
fun StatusPill(
    now: LocalDateTime,
    network: NetworkStatus,
    showTime: Boolean,
    enabled: Boolean,
    focusRequester: FocusRequester,
    onFocused: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Pressable(
        onClick = onClick,
        enabled = enabled,
        focusRequester = focusRequester,
        focusedScale = 1.08f,
        modifier = modifier,
    ) { state ->
        LaunchedEffect(state.focused) { onFocused(state.focused) }
        val bg by animateColorAsState(
            if (state.focused) Palette.Focus else Color.Transparent,
            tween(160, easing = Motion.EaseOut), label = "pill-bg",
        )
        val fg = if (state.focused) Palette.OnFocus else Color.White.copy(alpha = 0.9f)
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(bg)
                .height(34.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val glyph = when (network.kind) {
                NetKind.Wifi -> Glyph.Wifi
                NetKind.Ethernet -> Glyph.Ethernet
                NetKind.Offline -> Glyph.Offline
            }
            GlyphIcon(glyph, fg, 17.dp, level = network.level)
            if (showTime) {
                BasicText(timeText(now), style = Type.Headline.copy(color = fg))
            }
            GlyphIcon(Glyph.Switches, fg, 17.dp)
        }
    }
}
