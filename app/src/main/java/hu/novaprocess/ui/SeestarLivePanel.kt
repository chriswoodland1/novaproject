package hu.novaprocess.ui

import android.graphics.Bitmap
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import hu.novaprocess.seestar.SeestarLive
import hu.novaprocess.seestar.SeestarSmb

private enum class LiveMode(val label: String) { STACK("Élő stack"), PREVIEW("Kereső"), RTSP_TELE("Videó · tele"), RTSP_WIDE("Videó · széles") }

/**
 * Kis élő ablak a Seestar tallózó tetején.
 *  - Élő stack / Kereső: a Seestar 4800-as képcsatornája (csillagászati módban is: az élő stack megfigyelés közben frissül)
 *  - Videó: RTSP (4554 tele, 4555 nagylátószögű) – a ZWO szerint csak Táj és Naprendszer módban ad képet
 */
@Composable
fun SeestarLivePanel(host: String, onMakeVideo: () -> Unit = {}) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(LiveMode.STACK) }
    var interval by remember { mutableIntStateOf(20) }
    var big by remember { mutableStateOf(false) }
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var info by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val rec by hu.novaprocess.video.LiveRecorder.recording.collectAsState()
    val shots by hu.novaprocess.video.LiveRecorder.shots.collectAsState()

    Column(Modifier.fillMaxWidth().background(cs.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text((if (open) "● " else "○ ") + "Élő kép a Seestarról", style = MaterialTheme.typography.titleSmall, color = if (open) Color(0xFFFF6B6B) else cs.onSurface)
            TextButton(onClick = { open = !open }) { Text(if (open) "Leállítás" else "Indítás") }
        }
        if (!open) return@Column
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LiveMode.values().forEach { m -> Pill(m.label, mode == m) { if (mode != m) { mode = m; frame = null; info = ""; error = null } } }
        }
        if (mode == LiveMode.STACK) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Frissítés:", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            listOf(10, 20, 60).forEach { s -> Pill("$s s", interval == s) { interval = s } }
            Pill(if (rec) "● Felvétel (${shots.size})" else "○ Felvétel", rec) { if (rec) hu.novaprocess.video.LiveRecorder.stop() else hu.novaprocess.video.LiveRecorder.start(ctx) }
            if (!rec && shots.size >= 2) Pill("▶ Videó (${shots.size})", false) { onMakeVideo() }
        }
        val h = if (big) 420.dp else 200.dp
        Box(Modifier.fillMaxWidth().height(h).padding(vertical = 4.dp).background(Color.Black, RoundedCornerShape(10.dp)).clickable { big = !big }, contentAlignment = Alignment.Center) {
            when (mode) {
                LiveMode.STACK, LiveMode.PREVIEW -> {
                    LaunchedEffect(mode, interval, host) {
                        error = null; status = ""
                        try {
                            SeestarLive.run(ctx, host, if (mode == LiveMode.STACK) SeestarLive.Mode.STACK else SeestarLive.Mode.PREVIEW, interval,
                                onStatus = { status = it }, onFrame = { f -> frame = f.bitmap; info = f.info; if (mode == LiveMode.STACK) hu.novaprocess.video.LiveRecorder.add(f.bitmap, f.info) })
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { error = "Nem érhető el a képcsatorna (${e.message ?: e.javaClass.simpleName}). A Seestar legyen bekapcsolva, a telefon ugyanazon a Wi-Fi-n, és a Seestar appban fusson megfigyelés." }
                    }
                    frame?.let { Image(it.asImageBitmap(), contentDescription = "Élő kép", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                        ?: if (error == null) CircularProgressIndicator(strokeWidth = 2.dp) else Unit
                }
                LiveMode.RTSP_TELE, LiveMode.RTSP_WIDE -> RtspView("rtsp://$host:${if (mode == LiveMode.RTSP_TELE) 4554 else 4555}/stream") { error = it }
            }
            if (info.isNotBlank() && (mode == LiveMode.STACK || mode == LiveMode.PREVIEW))
                Text(info, style = MaterialTheme.typography.labelSmall, color = Color.White, modifier = Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 6.dp, vertical = 2.dp))
        }
        Text(error ?: status.ifBlank {
            when (mode) {
                LiveMode.STACK -> "Megfigyelés közben a Seestar aktuális élő stackje (koppints a nagyításhoz)."
                LiveMode.PREVIEW -> "A kamera nyers kereső-képe (fókusz, keresés)."
                else -> "Videó: a ZWO szerint csak Táj és Naprendszer módban ad képet, csillagászati módban nem."
            }
        }, style = MaterialTheme.typography.labelSmall, color = if (error != null) cs.error else cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun RtspView(url: String, onError: (String?) -> Unit) {
    val ctx = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(ctx).build().apply {
            val src = RtspMediaSource.Factory().setForceUseRtpTcp(true).setTimeoutMs(8000)
                .setSocketFactory(SeestarSmb.wifiSocketFactory(ctx)).createMediaSource(MediaItem.fromUri(url))
            setMediaSource(src); playWhenReady = true; volume = 0f
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) { onError("Nincs videó ($url): ${error.errorCodeName}. Csak Táj / Naprendszer módban működik.") }
                override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) onError(null) }
            })
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(factory = { PlayerView(it).apply { useController = false; this.player = player } }, modifier = Modifier.fillMaxSize())
}
