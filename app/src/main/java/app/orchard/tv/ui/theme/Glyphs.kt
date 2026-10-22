package app.orchard.tv.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp

/**
 * Aerial's glyphs: filled Material Symbols Rounded (GlyphPaths), whose soft, even weight sits
 * closest to SF Symbols among icons an Android app may ship (SF Symbols are licensed for Apple
 * platforms only). Vector paths, so they stay crisp at every size, in a square box.
 */
enum class Glyph { Wifi, Ethernet, Offline, Gear, Input, Speaker, SpeakerMuted, Switches, EyeSlash, Move, Info, Trash, ChevronRight, ChevronLeft, Check, Hdmi, Antenna, Av, Sun, Photo, Play, Bag, CloudSun, Calendar, Cast, Shield, Globe, Moon, Cloud, CloudRain, CloudSnow, CloudFog, CloudBolt }

@Composable
fun GlyphIcon(glyph: Glyph, color: Color, size: Dp, modifier: Modifier = Modifier, level: Float = 1f) {
    Canvas(modifier.size(size)) { drawGlyph(glyph, color, level) }
}

/** [level] picks the variant of Wi-Fi (signal) and Speaker (volume): 0 to 1. */
fun DrawScope.drawGlyph(glyph: Glyph, color: Color, level: Float = 1f) {
    val data = when (glyph) {
        Glyph.Wifi -> when {
            level < 0.34f -> GlyphPaths.WIFI_1
            level < 0.67f -> GlyphPaths.WIFI_2
            else -> GlyphPaths.WIFI
        }
        Glyph.Ethernet -> GlyphPaths.LAN
        Glyph.Offline -> GlyphPaths.WIFI_OFF
        Glyph.Gear -> GlyphPaths.SETTINGS
        Glyph.Input -> GlyphPaths.INPUT
        Glyph.Speaker -> when {
            level <= 0.01f -> GlyphPaths.VOLUME_MUTE
            level < 0.5f -> GlyphPaths.VOLUME_DOWN
            else -> GlyphPaths.VOLUME_UP
        }
        Glyph.SpeakerMuted -> GlyphPaths.VOLUME_OFF
        Glyph.Switches -> null
        Glyph.EyeSlash -> GlyphPaths.VISIBILITY_OFF
        Glyph.Move -> GlyphPaths.OPEN_WITH
        Glyph.Info -> GlyphPaths.INFO
        Glyph.Trash -> GlyphPaths.DELETE
        Glyph.ChevronRight -> GlyphPaths.CHEVRON_RIGHT
        Glyph.ChevronLeft -> GlyphPaths.CHEVRON_LEFT
        Glyph.Check -> GlyphPaths.CHECK
        Glyph.Hdmi -> GlyphPaths.HDMI
        Glyph.Antenna -> GlyphPaths.ANTENNA
        Glyph.Av -> GlyphPaths.COMPONENT
        Glyph.Sun -> GlyphPaths.LIGHT_MODE
        Glyph.Photo -> GlyphPaths.IMAGE
        Glyph.Play -> GlyphPaths.PLAY_CIRCLE
        Glyph.Bag -> GlyphPaths.SHOPPING_BAG
        Glyph.CloudSun -> GlyphPaths.PARTLY_CLOUDY_DAY
        Glyph.Calendar -> GlyphPaths.CALENDAR_MONTH
        Glyph.Cast -> GlyphPaths.CAST
        Glyph.Shield -> GlyphPaths.VERIFIED_USER
        Glyph.Globe -> GlyphPaths.LANGUAGE
        Glyph.Moon -> GlyphPaths.BEDTIME
        Glyph.Cloud -> GlyphPaths.CLOUD
        Glyph.CloudRain -> GlyphPaths.RAINY
        Glyph.CloudSnow -> GlyphPaths.WEATHER_SNOWY
        Glyph.CloudFog -> GlyphPaths.FOGGY
        Glyph.CloudBolt -> GlyphPaths.THUNDERSTORM
    }
    val s = size.minDimension
    if (data == null) {
        // Two toggles, the Control Center glyph; Material has no close match.
        val w = s * 0.085f
        val h = 0.3f * s
        val stroke = Stroke(width = w)
        drawRoundRect(color, Offset(0.06f * s, 0.14f * s), Size(0.88f * s, h), CornerRadius(h / 2), style = stroke)
        drawCircle(color, radius = h * 0.32f, center = Offset(0.21f * s, 0.29f * s))
        drawRoundRect(color, Offset(0.06f * s, 0.56f * s), Size(0.88f * s, h), CornerRadius(h / 2), style = stroke)
        drawCircle(color, radius = h * 0.32f, center = Offset(0.79f * s, 0.71f * s))
        return
    }
    val path = parsed.getOrPut(data) { PathParser().parsePathString(data).toPath() }
    // The symbols' box runs from y = -960 to 0.
    scale(s / 960f, pivot = Offset.Zero) {
        translate(0f, 960f) { drawPath(path, color) }
    }
}

// Tiles are drawn off the main thread while the UI draws glyphs on it.
private val parsed = java.util.concurrent.ConcurrentHashMap<String, Path>()
