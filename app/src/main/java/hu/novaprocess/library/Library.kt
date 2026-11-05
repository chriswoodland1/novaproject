package hu.novaprocess.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import hu.novaprocess.image.FloatImage
import hu.novaprocess.image.TiffWriter
import hu.novaprocess.process.ProcessParams
import java.io.File
import java.io.FileOutputStream

/** Egy korábbi munka: a forrás (saját TIFF a stackről vagy külső URI) + a recept + miniatűr. */
data class LibraryEntry(
    val id: String,
    val title: String,
    val createdAt: Long,
    val width: Int, val height: Int,
    val frames: Int,                 // 0 = egyetlen kép
    val integrationSec: Int,
    val sourcePath: String?,         // app-belső TIFF (stack) útvonala
    val sourceUri: String?,          // külső kép URI-ja (egyetlen kép)
    val paramsJson: String,
    val thumbPath: String?
)

object Library {
    private val gson = Gson()
    private fun indexFile(ctx: Context) = File(ctx.filesDir, "library.json")
    private fun stacksDir(ctx: Context) = File(ctx.filesDir, "stacks").apply { mkdirs() }
    private fun thumbsDir(ctx: Context) = File(ctx.filesDir, "thumbs").apply { mkdirs() }

    fun list(ctx: Context): List<LibraryEntry> {
        val f = indexFile(ctx); if (!f.exists()) return emptyList()
        return runCatching { gson.fromJson<List<LibraryEntry>>(f.readText(), object : TypeToken<List<LibraryEntry>>() {}.type) ?: emptyList() }
            .getOrDefault(emptyList()).sortedByDescending { it.createdAt }
    }

    private fun write(ctx: Context, entries: List<LibraryEntry>) { indexFile(ctx).writeText(gson.toJson(entries)) }

    fun upsert(ctx: Context, e: LibraryEntry) {
        val all = list(ctx).filter { it.id != e.id } + e
        write(ctx, all)
    }

    fun updateParams(ctx: Context, id: String, paramsJson: String) {
        val all = list(ctx).map { if (it.id == id) it.copy(paramsJson = paramsJson) else it }
        write(ctx, all)
    }

    fun delete(ctx: Context, id: String) {
        val e = list(ctx).find { it.id == id } ?: return
        e.sourcePath?.let { File(it).delete() }
        e.thumbPath?.let { File(it).delete() }
        write(ctx, list(ctx).filter { it.id != id })
    }

    /** Stack eredményének mentése 16 bites TIFF-be + miniatűr; visszaadja a bejegyzést. */
    fun saveStack(ctx: Context, img: FloatImage, thumb: Bitmap, title: String, frames: Int, integrationSec: Int, params: ProcessParams): LibraryEntry {
        val id = "s" + System.currentTimeMillis()
        val tif = File(stacksDir(ctx), "$id.tif")
        FileOutputStream(tif).use { TiffWriter.write(img, it) }
        val th = saveThumb(ctx, id, thumb)
        val e = LibraryEntry(id, title, System.currentTimeMillis(), img.width, img.height, frames, integrationSec, tif.absolutePath, null, com.google.gson.Gson().toJson(params), th)
        upsert(ctx, e); return e
    }

    fun saveSingle(ctx: Context, uri: String, title: String, w: Int, h: Int, thumb: Bitmap, params: ProcessParams): LibraryEntry {
        val id = "u" + uri.hashCode().toUInt().toString(16)
        val th = saveThumb(ctx, id, thumb)
        val e = LibraryEntry(id, title, System.currentTimeMillis(), w, h, 0, 0, null, uri, com.google.gson.Gson().toJson(params), th)
        upsert(ctx, e); return e
    }

    private fun saveThumb(ctx: Context, id: String, bmp: Bitmap): String {
        val f = File(thumbsDir(ctx), "$id.jpg")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return f.absolutePath
    }

    fun loadThumb(path: String?): Bitmap? = path?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }

    fun usedBytes(ctx: Context): Long = (stacksDir(ctx).listFiles()?.sumOf { it.length() } ?: 0L) + (thumbsDir(ctx).listFiles()?.sumOf { it.length() } ?: 0L)
}
