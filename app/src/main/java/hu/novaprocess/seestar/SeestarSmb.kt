package hu.novaprocess.seestar

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Properties

data class SmbEntry(val name: String, val path: String, val isDir: Boolean, val size: Long, val modified: Long)

/**
 * A Seestar a saját Wi-Fi-jén (hotspot: 10.0.0.1) vagy állomás módban a házi hálózaton SMB-n osztja meg a
 * felvételeket: „EMMC Images” megosztás, benne MyWorks/<objektum>/ (kész stack) és <objektum>_sub/ (FITS kockák).
 * Vendég hozzáférés, jelszó nélkül.
 *
 * Android-sajátosságok, amik miatt „nem kapcsolódik”:
 *  - a Seestar hotspotján nincs internet → a rendszer a mobilneten küldené a forgalmat; ezért a műveletek
 *    idejére a folyamatot a Wi-Fi hálózathoz kötjük (bindProcessToNetwork);
 *  - a `.local` (mDNS) nevet a Java névfeloldó nem ismeri → IP-t keresünk: hotspot-cím, átjáró, majd a
 *    helyi alhálózat 445-ös portjainak gyors végigpróbálása.
 */
object SeestarSmb {
    const val SHARE = "EMMC Images"

    private val credentialOrder = listOf("guest", "anon", "guestuser")

    private fun ctx(mode: String): CIFSContext {
        val p = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB1")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.smb.client.connTimeout", "6000")
            setProperty("jcifs.smb.client.responseTimeout", "15000")
            setProperty("jcifs.smb.client.soTimeout", "20000")
            setProperty("jcifs.smb.client.sessionTimeout", "30000")
            setProperty("jcifs.resolveOrder", "DNS")
            setProperty("jcifs.smb.client.dfs.disabled", "true")
            setProperty("jcifs.smb.client.ipcSigningEnforced", "false")
            setProperty("jcifs.smb.client.signingPreferred", "false")
            setProperty("jcifs.smb.client.disablePlainTextPasswords", "false")
            setProperty("jcifs.smb.client.useExtendedSecurity", "true")
        }
        val base = BaseContext(PropertyConfiguration(p))
        return when (mode) {
            "anon" -> base.withAnonymousCredentials()
            "guestuser" -> base.withCredentials(NtlmPasswordAuthenticator("", "guest", ""))
            else -> base.withGuestCrendentials()
        }
    }

    @Volatile private var workingMode: String = "guest"

    // ------------------------------------------------------------------ hálózat-kötés
    private fun wifiNetwork(context: Context): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.allNetworks.firstOrNull { n -> cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }

    /** A blokk idejére a folyamat hálózati forgalmát a Wi-Fi-re köti (ha van Wi-Fi), utána visszaállítja. */
    private inline fun <T> onWifi(context: Context, block: () -> T): T {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifi = wifiNetwork(context)
        val bound = wifi != null && runCatching { cm.bindProcessToNetwork(wifi) }.getOrDefault(false)
        try { return block() } finally { if (bound) runCatching { cm.bindProcessToNetwork(null) } }
    }

    data class NetInfo(val wifiConnected: Boolean, val hasInternet: Boolean, val gateway: String?, val myIp: String?)

    @Suppress("DEPRECATION")
    fun netInfo(context: Context): NetInfo {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifi = wifiNetwork(context)
        val caps = wifi?.let { cm.getNetworkCapabilities(it) }
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val d = runCatching { wm?.dhcpInfo }.getOrNull()
        fun ip(v: Int): String? = if (v == 0) null else "${v and 0xff}.${v shr 8 and 0xff}.${v shr 16 and 0xff}.${v shr 24 and 0xff}"
        return NetInfo(wifi != null, caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true, d?.let { ip(it.gateway) }, d?.let { ip(it.ipAddress) })
    }

    // ------------------------------------------------------------------ felderítés
    /** Gyors TCP-próba a 445-ös portra. */
    private fun port445(host: String, timeoutMs: Int): Boolean = runCatching { Socket().use { it.connect(InetSocketAddress(host, 445), timeoutMs); true } }.getOrDefault(false)

    /**
     * Seestar keresése: 1) hotspot-cím 10.0.0.1, 2) Wi-Fi átjáró, 3) beírt/előző cím, 4) az alhálózat (/24) végigpróbálása.
     * Visszaadja azokat a címeket, ahol a 445-ös port nyitva van (a Seestar elöl).
     */
    suspend fun discover(context: Context, hint: String? = null, onStatus: (String) -> Unit = {}): List<String> = withContext(Dispatchers.IO) {
        onWifi(context) {
            val info = netInfo(context)
            val found = LinkedHashSet<String>()
            val quick = listOfNotNull("10.0.0.1", info.gateway, hint?.takeIf { it.isNotBlank() && it != "seestar.local" && it != "seestar" }).distinct()
            onStatus("Gyors próba: ${quick.joinToString(", ")}")
            for (h in quick) if (port445(h, 1200)) found += h
            if (found.isEmpty()) runCatching { java.net.InetAddress.getByName("seestar").hostAddress }.getOrNull()?.let { if (port445(it, 1200)) found += it }
            if (found.isEmpty() && info.myIp != null) {
                val prefix = info.myIp.substringBeforeLast('.')
                onStatus("Alhálózat keresése: $prefix.1–254 (445-ös port)…")
                coroutineScope {
                    val hits = (1..254).map { i -> async(Dispatchers.IO) { val h = "$prefix.$i"; if (h != info.myIp && port445(h, 500)) h else null } }.awaitAll().filterNotNull()
                    found += hits
                }
            }
            found.toList()
        }
    }

    /** Ellenőrzi, hogy a címen tényleg Seestar van-e: megpróbálja az „EMMC Images” megosztást több hitelesítéssel. */
    suspend fun probe(context: Context, host: String): String? = withContext(Dispatchers.IO) {
        onWifi(context) {
            for (m in listOf(workingMode) + credentialOrder.filter { it != workingMode }) {
                val ok = runCatching { withTimeoutOrNull(12000) { SmbFile("smb://$host/$SHARE/", ctx(m)).exists() } }.getOrNull() == true
                if (ok) { workingMode = m; return@onWifi "smb://$host/$SHARE/" }
            }
            null
        }
    }

    /** Lista: a path "smb://host/" (megosztások) vagy "smb://host/share/dir/". Több hitelesítést próbál. */
    suspend fun list(context: Context, path: String): List<SmbEntry> = withContext(Dispatchers.IO) {
        onWifi(context) {
            var last: Exception? = null
            for (m in listOf(workingMode) + credentialOrder.filter { it != workingMode }) {
                try {
                    val f = SmbFile(if (path.endsWith("/")) path else "$path/", ctx(m))
                    val files = f.listFiles() ?: emptyArray()
                    workingMode = m
                    return@onWifi files.map { SmbEntry(it.name.trimEnd('/'), it.path, it.isDirectory, runCatching { it.length() }.getOrDefault(0L), runCatching { it.lastModified() }.getOrDefault(0L)) }
                        .sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
                } catch (e: Exception) { last = e }
            }
            throw last ?: IllegalStateException("Ismeretlen hiba")
        }
    }

    /** Fájl letöltése az app cache-ébe; visszaadja a helyi fájlt. */
    suspend fun download(context: Context, path: String, onProgress: (Long, Long) -> Unit = { _, _ -> }): File = withContext(Dispatchers.IO) {
        onWifi(context) {
            val f = SmbFile(path, ctx(workingMode))
            val dir = File(context.cacheDir, "seestar").apply { mkdirs() }
            val out = File(dir, f.name)
            val total = f.length()
            if (out.exists() && out.length() == total && total > 0) { onProgress(total, total); return@onWifi out }   // már letöltve (pl. előnézethez)
            val tmp = File(dir, f.name + ".part")
            f.inputStream.use { ins -> tmp.outputStream().use { os ->
                val buf = ByteArray(1 shl 16); var done = 0L
                while (true) { val n = ins.read(buf); if (n <= 0) break; os.write(buf, 0, n); done += n; onProgress(done, total) }
            } }
            out.delete(); tmp.renameTo(out)
            out
        }
    }

    /** Kis, autostretchelt előnézet egy letöltött fájlból (FITS: Bayer-szuperpixel, gyors; JPEG/PNG: mintavételezett dekódolás). */
    fun previewOf(file: File, maxSide: Int = 480): android.graphics.Bitmap? = runCatching {
        val n = file.name.lowercase()
        if (n.endsWith(".fit") || n.endsWith(".fits")) {
            val (img, cards, raw) = file.inputStream().use { hu.novaprocess.image.FitsReader.readRawOrImage(it) }
            val small = if (raw != null) hu.novaprocess.stack.FrameAnalyzer.superpixel(raw.third, raw.first, raw.second, (cards["BAYERPAT"] ?: "RGGB").uppercase()).downscale(maxSide)
                        else img!!.downscale(maxSide)
            hu.novaprocess.process.Processor.process(small, hu.novaprocess.process.ProcessParams(stretchMode = hu.novaprocess.process.StretchMode.AUTO, mtfMidtones = 0.22f, backgroundNeutralize = true)).toBitmap()
        } else {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.path, o)
            var s = 1; while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2
            android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = s })
        }
    }.getOrNull()

    /** Rekurzív FITS-gyűjtés egy mappából (max. mélység 3) – pl. MyWorks/M31 → minden _sub kocka. */
    suspend fun collectFits(context: Context, path: String, depth: Int = 3): List<SmbEntry> = withContext(Dispatchers.IO) {
        onWifi(context) {
            val out = ArrayList<SmbEntry>()
            fun walk(p: String, d: Int) {
                val f = SmbFile(if (p.endsWith("/")) p else "$p/", ctx(workingMode))
                for (e in f.listFiles() ?: emptyArray()) {
                    val n = e.name.lowercase()
                    if (e.isDirectory) { if (d > 0 && !n.contains("thumbnail")) walk(e.path, d - 1) }
                    else if (n.endsWith(".fit") || n.endsWith(".fits")) out.add(SmbEntry(e.name, e.path, false, runCatching { e.length() }.getOrDefault(0L), 0L))
                }
            }
            walk(path, depth)
            out.sortedBy { it.path }
        }
    }

    /** Alapértelmezett belépési pontok, amiket érdemes sorban próbálni. */
    val defaultHosts = listOf("10.0.0.1", "seestar")

    /** Emberi hibaüzenet a leggyakoribb okokra. */
    fun explain(e: Throwable, info: NetInfo): String {
        val m = (e.message ?: e.javaClass.simpleName)
        return when {
            !info.wifiConnected -> "Nincs Wi-Fi kapcsolat. Csatlakozz a Seestar hotspotjára (S30/S50-…), vagy legyen a Seestar állomás módban ugyanazon a hálózaton."
            m.contains("UnknownHost", true) || m.contains("Unable to resolve", true) || m.contains("failed to connect", true) -> "A cím nem érhető el ($m). Használd az „Automatikus keresés” gombot, vagy írd be az IP-t (hotspoton 10.0.0.1, otthoni hálózaton a routerben látható Seestar-cím)."
            m.contains("Logon failure", true) || m.contains("ACCESS_DENIED", true) || m.contains("STATUS_LOGON", true) -> "Hitelesítési hiba ($m). A Seestar vendég hozzáférést használ, jelszó nélkül – frissebb firmware-nél a Seestar appban kapcsold be a fájlmegosztást (Beállítások → Tárhely)."
            m.contains("timed out", true) || m.contains("timeout", true) -> "Időtúllépés ($m). A Seestar alszik vagy a telefon a mobilneten keresztül próbálkozik – kapcsold ki egy percre a mobiladatot, és próbáld újra."
            else -> "Nem érhető el: $m"
        }
    }
}
