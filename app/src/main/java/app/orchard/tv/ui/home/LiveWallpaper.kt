package app.orchard.tv.ui.home

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.core.graphics.createBitmap
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.system.DiagLog
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.wallpaper.AerialFormats
import app.orchard.tv.wallpaper.AerialQuality
import app.orchard.tv.wallpaper.AerialStream
import app.orchard.tv.wallpaper.Aerials
import app.orchard.tv.wallpaper.Wallpapers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** How long Home sits still before the aerial wallpaper starts moving. */
private const val START_AFTER_MS = 10_000L

/** A stopped film keeps its player this long, so it moves on again without loading. */
private const val KEEP_STOPPED_MS = 2 * 60 * 1000L

/**
 * The aerial wallpaper (issue #216): once Home has sat still on the top row for [START_AFTER_MS],
 * Apple's film plays behind the tiles, and any key stops it on the frame it was showing. That frame
 * becomes the wallpaper's picture (Wallpapers.useLiveFrame), so the glass plate, the grid's frosted
 * background and the next start all match what was on screen.
 *
 * The film sits in a SurfaceView under the window; [cover] is how far it shows through. While it
 * plays, HomeBackground draws neither the picture nor the plate, so Compose draws nothing full-screen
 * and the frame budget is the same as on a still Home. Kept to SDR even where the screensaver plays
 * Dolby Vision: a TV in Dolby Vision mode shows the tiles over it with Dolby's picture settings.
 */
@Composable
fun LiveWallpaper(
    moving: Boolean,
    /** False once the screensaver is up or Home is behind an app: the player's decoder goes at once. */
    keep: Boolean,
    lastInputAt: Long,
    cover: Animatable<Float, *>,
) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var surface by remember { mutableStateOf<SurfaceView?>(null) }
    // Frames are reaching the screen: the first one has been drawn and the film is not stopped.
    var showing by remember { mutableStateOf(false) }
    var idle by remember { mutableStateOf(false) }
    LaunchedEffect(lastInputAt, moving) {
        idle = false
        if (!moving) return@LaunchedEffect
        delay(START_AFTER_MS)
        idle = true
    }
    val play = moving && idle
    val keepNow by rememberUpdatedState(keep)

    fun release() {
        player?.release()
        player = null
        showing = false
    }

    fun open(): ExoPlayer {
        val quality = AerialFormats.resolve(context, AerialSettings.aerialQuality)
            .let { if (it == AerialQuality.DolbyVision) AerialQuality.Uhd else it }
        val overHttp = Build.VERSION.SDK_INT < Build.VERSION_CODES.N
        val films = AerialStream.withOpenerFirst(context, Aerials.all(context).shuffled(), quality)
        var failures = 0
        var rendered = false
        return AerialStream.player(context, quality).apply {
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    rendered = true
                    showing = isPlaying
                    failures = 0
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    showing = isPlaying && rendered
                }

                override fun onPlayerError(error: PlaybackException) {
                    DiagLog.i("wallpaper film failed (${error.errorCodeName})")
                    // Three in a row: Apple's host is out of reach; the picture stays as it is.
                    if (++failures >= 3) return
                    seekToNextMediaItem()
                    prepare()
                }
            })
            setMediaItems(films.map { AerialStream.mediaItem(it, quality, overHttp) })
            surface?.let { setVideoSurfaceView(it) }
            prepare()
        }
    }

    LaunchedEffect(play) {
        if (play) {
            val p = player ?: open().also { player = it }
            DiagLog.i("wallpaper film starts (state ${p.playbackState})")
            p.playWhenReady = true
            snapshotFlow { showing }.first { it }
            DiagLog.i("wallpaper film moving")
            cover.animateTo(1f, tween(1200, easing = Motion.EaseOut))
        } else {
            val p = player ?: return@LaunchedEffect
            // Into the screensaver or an app: no frame to take, and the decoder is needed elsewhere.
            if (!keepNow) {
                cover.snapTo(0f)
                release()
                return@LaunchedEffect
            }
            p.pause()
            if (cover.value > 0f) {
                // The stopped frame becomes the picture; once Home has it, the film can go. Where
                // the video plane cannot be read, the same moment is decoded from the 1080p file.
                val film = p.currentMediaItem?.mediaId
                val at = p.currentPosition
                val frame = surface?.let { capture(it) }
                    ?: film?.let { decodeFrame(context, it, at, surface?.width ?: Wallpapers.screenW, surface?.height ?: Wallpapers.screenH) }
                if (frame != null) {
                    Wallpapers.useLiveFrame(frame)
                    // HomeBackground crossfades to a new picture over 450 ms.
                    delay(500)
                } else {
                    DiagLog.i("wallpaper film: no frame copied; back to the last picture")
                }
                cover.animateTo(0f, tween(if (frame != null) 250 else 600, easing = Motion.EaseOut))
            }
            delay(KEEP_STOPPED_MS)
            release()
        }
    }
    // A stopped player still holds its decoder, and some TVs have only one video path: on Xiaomi
    // MiTV-MFTR0 the screensaver's decoder lost its resources and its films failed while it was held.
    LaunchedEffect(keep) {
        if (!keep && !play && player != null && cover.value == 0f) release()
    }
    DisposableEffect(Unit) { onDispose { release() } }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx -> SurfaceView(ctx).also { surface = it; player?.setVideoSurfaceView(it) } },
        onRelease = { player?.clearVideoSurfaceView(it); surface = null },
    )
}

/**
 * The film's current frame, at the screen's size, or null: PixelCopy needs Android 7.0, and some
 * TVs put video on a plane apps cannot read, which comes back black.
 */
private suspend fun capture(view: SurfaceView): Bitmap? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || view.width <= 0 || !view.holder.surface.isValid) return null
    val bitmap = createBitmap(view.width, view.height)
    val result = suspendCancellableCoroutine { done ->
        runCatching {
            PixelCopy.request(view, bitmap, { done.resume(it) }, Handler(Looper.getMainLooper()))
        }.onFailure { done.resume(-1) }
    }
    val black = result == PixelCopy.SUCCESS && isBlack(bitmap)
    if (result != PixelCopy.SUCCESS || black) DiagLog.i("wallpaper frame copy: result $result, black $black")
    return bitmap.takeIf { result == PixelCopy.SUCCESS && !black }
}

/**
 * Frame [atMs] of film [id], decoded from its 1080p H.264 file by MediaMetadataRetriever, which
 * fetches only the index and the stretch it needs. For TVs whose video plane PixelCopy cannot read
 * (Xiaomi MiTV-MFTR0, MStar: the copy comes back black). Null after [DECODE_TIMEOUT_MS].
 */
private suspend fun decodeFrame(context: android.content.Context, id: String, atMs: Long, w: Int, h: Int): Bitmap? {
    val film = Aerials.all(context).firstOrNull { it.id == id } ?: return null
    val url = AerialStream.url(film, AerialQuality.Hd, Build.VERSION.SDK_INT < Build.VERSION_CODES.N)
    val started = android.os.SystemClock.elapsedRealtime()
    val frame = withTimeoutOrNull(DECODE_TIMEOUT_MS) {
        withContext(Dispatchers.IO) {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(url, emptyMap())
                val us = atMs * 1000
                // Exactly the frame on screen, so the swap from film to picture does not show.
                if (Build.VERSION.SDK_INT >= 27) mmr.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, w, h)
                else mmr.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)
            } catch (e: Exception) {
                DiagLog.i("wallpaper frame decode failed: ${e.javaClass.simpleName}")
                null
            } finally {
                runCatching { mmr.release() }
            }
        }
    }
    DiagLog.i("wallpaper frame decoded: ${frame != null} in ${android.os.SystemClock.elapsedRealtime() - started} ms")
    return frame?.let {
        val sized = if (it.width == w && it.height == h) it else Bitmap.createScaledBitmap(it, w, h, true)
        if (sized.isMutable && sized.config == Bitmap.Config.ARGB_8888) sized else sized.copy(Bitmap.Config.ARGB_8888, true)
    }
}

private const val DECODE_TIMEOUT_MS = 10_000L

private fun isBlack(b: Bitmap): Boolean {
    var lit = 0
    for (y in 1..8) for (x in 1..8) {
        val c = b.getPixel(b.width * x / 9, b.height * y / 9)
        if ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF) > 24) lit++
    }
    return lit < 3
}
