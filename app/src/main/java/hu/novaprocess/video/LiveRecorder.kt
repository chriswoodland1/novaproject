package hu.novaprocess.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * Az élő stack felvétele távcső mellől: a Seestar élő panel minden frissítését JPEG-ként eltárolja (időbélyeggel),
 * a végén ebből „Így épült fel” videó készül – sub-ok letöltése nélkül.
 */
object LiveRecorder {
    data class Shot(val file: File, val time: Long, val info: String)
    val recording = MutableStateFlow(false)
    val shots = MutableStateFlow<List<Shot>>(emptyList())
    private var dir: File? = null

    fun start(ctx: Context) {
        val d = File(ctx.cacheDir, "live_rec").apply { deleteRecursively(); mkdirs() }
        dir = d; shots.value = emptyList(); recording.value = true
    }
    fun stop() { recording.value = false }
    fun clear() { dir?.deleteRecursively(); shots.value = emptyList(); recording.value = false }

    /** Csak akkor ment, ha a kép tényleg változott (az élő stack frissült). */
    fun add(b: Bitmap, info: String) {
        if (!recording.value) return
        val d = dir ?: return
        val last = shots.value.lastOrNull()
        if (last != null && last.info == info) return
        val f = File(d, "%04d.jpg".format(shots.value.size))
        runCatching { f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 92, it) } }.onSuccess { shots.value = shots.value + Shot(f, System.currentTimeMillis(), info) }
    }

    fun scenes(): List<Scene> {
        val list = shots.value; if (list.isEmpty()) return emptyList()
        val t0 = list.first().time
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return list.mapIndexedNotNull { i, s ->
            BitmapFactory.decodeFile(s.file.path)?.let { Scene(it, "${fmt.format(java.util.Date(s.time))} · ${i + 1}. frissítés", "+${(s.time - t0) / 60000} perc megfigyelés") }
        }
    }
}
