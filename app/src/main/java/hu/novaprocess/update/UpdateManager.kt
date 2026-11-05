package hu.novaprocess.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import hu.novaprocess.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(val tag: String, val versionName: String, val notes: String, val apkUrl: String, val apkName: String, val htmlUrl: String)

/**
 * Frissítés GitHub Releases alapján:
 *  1. GET https://api.github.com/repos/{owner}/{repo}/releases/latest
 *  2. ha a tag verziója nagyobb a telepítettnél → felajánljuk
 *  3. engedély után DownloadManager tölti le az APK-t, majd a rendszer telepítőjét nyitjuk meg.
 */
object UpdateManager {
    private const val PREFS = "nova_update"
    private const val KEY_REPO = "repo"
    private const val KEY_SKIPPED = "skipped_tag"
    private const val KEY_LAST_CHECK = "last_check"

    fun repo(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_REPO, null) ?: BuildConfig.GITHUB_REPO

    fun setRepo(context: Context, repo: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_REPO, repo.trim()).apply()
    }

    fun skipTag(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SKIPPED, tag).apply()
    }

    fun shouldAutoCheck(context: Context, minIntervalMs: Long = 6 * 60 * 60 * 1000L): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = p.getLong(KEY_LAST_CHECK, 0L)
        return System.currentTimeMillis() - last > minIntervalMs
    }

    /** Null, ha nincs újabb (vagy a felhasználó kihagyta ezt a verziót). Hibát dob hálózati gond esetén. */
    suspend fun checkForUpdate(context: Context, ignoreSkipped: Boolean = false): ReleaseInfo? = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        val repo = repo(context)
        val url = URL("https://api.github.com/repos/$repo/releases/latest")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 15000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "NovaProcess/${BuildConfig.VERSION_NAME}")
        }
        val body = try {
            if (conn.responseCode != 200) throw IllegalStateException("GitHub válasz: ${conn.responseCode}")
            conn.inputStream.bufferedReader().readText()
        } finally { conn.disconnect() }
        val json = JSONObject(body)
        val tag = json.optString("tag_name")
        val versionName = tag.removePrefix("v").removePrefix("V")
        val assets = json.optJSONArray("assets")
        var apkUrl: String? = null; var apkName = "NovaProcess.apk"
        if (assets != null) for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            if (name.endsWith(".apk", ignoreCase = true)) { apkUrl = a.optString("browser_download_url"); apkName = name; break }
        }
        if (apkUrl == null) return@withContext null
        if (!ignoreSkipped && prefs.getString(KEY_SKIPPED, null) == tag) return@withContext null
        if (compareVersions(versionName, BuildConfig.VERSION_NAME) <= 0) return@withContext null
        ReleaseInfo(tag, versionName, json.optString("body"), apkUrl, apkName, json.optString("html_url"))
    }

    /** "1.2.10" vs "1.2.9" → pozitív, ha a bal nagyobb. A "-debug" és hasonló toldalékot levágja. */
    fun compareVersions(a: String, b: String): Int {
        fun parts(s: String) = s.substringBefore('-').split('.').map { it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
        val pa = parts(a); val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }; val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    fun canInstallUnknownApps(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val i = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        }
    }

    /**
     * Letölti az APK-t az app külső fájlmappájába, és a letöltés végén elindítja a telepítőt.
     * onDone(hibaüzenet vagy null) a befejezéskor fut a fő szálon.
     */
    fun downloadAndInstall(context: Context, info: ReleaseInfo, onDone: (String?) -> Unit) {
        val app = context.applicationContext
        val dir = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: app.filesDir
        val target = File(dir, info.apkName)
        if (target.exists()) target.delete()

        val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(Uri.parse(info.apkUrl))
            .setTitle("NovaProcess ${info.versionName}")
            .setDescription("Frissítés letöltése…")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, info.apkName)
            .setMimeType("application/vnd.android.package-archive")
        val id = dm.enqueue(req)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val doneId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (doneId != id) return
                try { app.unregisterReceiver(this) } catch (e: Exception) { /* már leiratkozott */ }
                val q = DownloadManager.Query().setFilterById(id)
                dm.query(q)?.use { c ->
                    if (c.moveToFirst()) {
                        val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        if (status != DownloadManager.STATUS_SUCCESSFUL) { onDone("A letöltés nem sikerült (státusz $status)"); return }
                    }
                }
                try {
                    val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", target)
                    val install = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    app.startActivity(install)
                    onDone(null)
                } catch (e: Exception) { onDone("A telepítő nem indítható: ${e.message}") }
            }
        }
        ContextCompat.registerReceiver(
            app, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED
        )
    }
}
