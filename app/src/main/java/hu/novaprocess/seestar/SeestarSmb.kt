package hu.novaprocess.seestar

import android.content.Context
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties

data class SmbEntry(val name: String, val path: String, val isDir: Boolean, val size: Long, val modified: Long)

/**
 * A Seestar a saját Wi-Fi-jén (vagy állomás módban a házi hálózaton) SMB-n megosztja a
 * felvételeket ("EMMC Images" megosztás, MyWorks/objektum/..._sub mappa FITS fájljai). Vendég hozzáférés.
 * Alapértelmezett cím: seestar.local; a Seestar saját hotspotján általában 10.0.0.1.
 */
object SeestarSmb {

    private fun ctx(user: String?, pass: String?): CIFSContext {
        val p = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB1")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.smb.client.responseTimeout", "15000")
            setProperty("jcifs.smb.client.soTimeout", "20000")
            setProperty("jcifs.resolveOrder", "DNS,BCAST")
        }
        val base = BaseContext(PropertyConfiguration(p))
        return if (user.isNullOrBlank()) base.withGuestCrendentials() else base.withCredentials(NtlmPasswordAuthenticator("", user, pass ?: ""))
    }

    /** Lista: a path "smb://host/" (megosztások) vagy "smb://host/share/dir/". */
    suspend fun list(path: String, user: String? = null, pass: String? = null): List<SmbEntry> = withContext(Dispatchers.IO) {
        val f = SmbFile(if (path.endsWith("/")) path else "$path/", ctx(user, pass))
        val files = f.listFiles() ?: emptyArray()
        files.map { SmbEntry(it.name.trimEnd('/'), it.path, it.isDirectory, runCatching { it.length() }.getOrDefault(0L), runCatching { it.lastModified() }.getOrDefault(0L)) }
            .sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    /** Fájl letöltése az app cache-ébe; visszaadja a helyi fájlt. */
    suspend fun download(context: Context, path: String, user: String? = null, pass: String? = null, onProgress: (Long, Long) -> Unit = { _, _ -> }): File = withContext(Dispatchers.IO) {
        val f = SmbFile(path, ctx(user, pass))
        val dir = File(context.cacheDir, "seestar").apply { mkdirs() }
        val out = File(dir, f.name)
        val total = f.length()
        f.inputStream.use { ins -> out.outputStream().use { os ->
            val buf = ByteArray(1 shl 16); var done = 0L
            while (true) { val n = ins.read(buf); if (n <= 0) break; os.write(buf, 0, n); done += n; onProgress(done, total) }
        } }
        out
    }

    /** Rekurzív FITS-gyűjtés egy mappából (max. mélység 3) – pl. MyWorks/M31 → minden _sub kocka. */
    suspend fun collectFits(path: String, depth: Int = 3, user: String? = null, pass: String? = null): List<SmbEntry> = withContext(Dispatchers.IO) {
        val out = ArrayList<SmbEntry>()
        fun walk(p: String, d: Int) {
            val f = SmbFile(if (p.endsWith("/")) p else "$p/", ctx(user, pass))
            for (e in f.listFiles() ?: emptyArray()) {
                val n = e.name.lowercase()
                if (e.isDirectory) { if (d > 0 && !n.contains("thumbnail")) walk(e.path, d - 1) }
                else if (n.endsWith(".fit") || n.endsWith(".fits")) out.add(SmbEntry(e.name, e.path, false, runCatching { e.length() }.getOrDefault(0L), 0L))
            }
        }
        walk(path, depth)
        out.sortedBy { it.path }
    }

    /** Alapértelmezett belépési pontok, amiket érdemes sorban próbálni. */
    val defaultHosts = listOf("seestar.local", "10.0.0.1", "seestar")
}
