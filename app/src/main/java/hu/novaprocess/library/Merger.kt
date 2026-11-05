package hu.novaprocess.library

import android.content.Context
import android.net.Uri
import hu.novaprocess.image.FloatImage
import hu.novaprocess.image.ImageLoader
import hu.novaprocess.image.TiffReader
import hu.novaprocess.stack.Aligner
import hu.novaprocess.stack.StarDetector
import hu.novaprocess.stack.Transform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Több éjszaka (több stack) összevonása: az elsőhöz igazítás csillagok alapján, majd integrációs idővel
 * (vagy kockaszámmal) súlyozott átlag. A memóriakeret szerint szükség esetén kicsinyít.
 */
object Merger {
    data class Result(val image: FloatImage, val log: List<String>, val totalFrames: Int, val totalSec: Int)

    suspend fun merge(ctx: Context, entries: List<LibraryEntry>, onProgress: (String) -> Unit): Result = withContext(Dispatchers.Default) {
        require(entries.size >= 2) { "Legalább két bejegyzés kell" }
        val log = ArrayList<String>()
        val heap = Runtime.getRuntime().maxMemory()
        // cél: referencia + aktuális + akkumulátor + warp ≈ 4 kép → px*12*4 < 0.5 heap
        val maxPx = (heap * 0.5 / 48.0).toLong()
        fun load(e: LibraryEntry): FloatImage {
            var img = if (e.sourcePath != null) TiffReader.read(File(e.sourcePath).inputStream()) else ImageLoader.load(ctx, Uri.parse(e.sourceUri!!))
            if (img.width.toLong() * img.height > maxPx) {
                val side = Math.sqrt(maxPx.toDouble() * img.width / img.height).toInt()
                img = img.downscale(side)
            }
            return img.toRgb()
        }
        onProgress("Referencia: ${entries[0].title}")
        val ref = load(entries[0])
        val w = ref.width; val h = ref.height
        val refStars = StarDetector.detect(ref.downscale(1200))
        val sRef = maxOf(w, h).toFloat() / maxOf(ref.downscale(1200).width, ref.downscale(1200).height)
        fun weight(e: LibraryEntry) = (if (e.integrationSec > 0) e.integrationSec.toFloat() else if (e.frames > 0) e.frames.toFloat() else 1f)
        val acc = FloatImage(w, h, 3); val wsum = FloatArray(w * h)
        var totalFrames = 0; var totalSec = 0
        fun add(img: FloatImage, wt: Float) {
            for (c in 0 until 3) { val a = img.data[c]; val o = acc.data[c]; for (i in o.indices) if (a[i] > 0f || c > 0) o[i] += a[i] * wt }
            for (i in wsum.indices) if (img.data[0][i] > 0f || img.data[1][i] > 0f || img.data[2][i] > 0f) wsum[i] += wt
        }
        add(ref, weight(entries[0])); totalFrames += entries[0].frames; totalSec += entries[0].integrationSec
        log += "${entries[0].title}: súly ${"%.0f".format(weight(entries[0]))}"
        for (e in entries.drop(1)) {
            onProgress("Igazítás: ${e.title}")
            var img = load(e)
            if (img.width != w || img.height != h) { img = img.downscale(maxOf(w, h)); if (img.width != w || img.height != h) { log += "${e.title}: eltérő méret, kihagyva"; continue } }
            val small = img.downscale(1200)
            val tf = Aligner.estimate(refStars, StarDetector.detect(small), small.width, small.height, wide = true)
            if (tf.matches < 5) { log += "${e.title}: nem igazítható (${tf.matches} egyezés), kihagyva"; continue }
            val tfFull = Transform(tf.angle, tf.dx * sRef, tf.dy * sRef, tf.matches)
            val reg = Aligner.warp(img, tfFull)
            add(reg, weight(e)); totalFrames += e.frames; totalSec += e.integrationSec
            log += "${e.title}: ${tf.matches} egyezés, súly ${"%.0f".format(weight(e))}"
        }
        for (c in 0 until 3) { val o = acc.data[c]; for (i in o.indices) if (wsum[i] > 0f) o[i] /= wsum[i] }
        log += "Összevonva: ${entries.size} munka, ${totalFrames} kocka, ${totalSec / 60} perc"
        Result(acc, log, totalFrames, totalSec)
    }
}
