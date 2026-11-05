package hu.novaprocess

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class NovaApp : Application() {
    /**
     * A feldolgozó állapot (kockák, stack, szerkesztés) az ALKALMAZÁS élettartamához kötve, nem a képernyőhöz:
     * ha az Android háttérben lebontja az Activity-t, a futó stack és a megnyitott kép megmarad.
     */
    val vm: hu.novaprocess.vm.EditorViewModel by lazy { hu.novaprocess.vm.EditorViewModel(this) }

    override fun onCreate() {
        super.onCreate()
        hu.novaprocess.image.FitsReader.bayerOverride = getSharedPreferences("nova_ui", MODE_PRIVATE).getString("fits_bayer", null)?.takeIf { it.length == 4 }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                val rt = Runtime.getRuntime()
                val text = buildString {
                    append("NovaProcess ${BuildConfig.VERSION_NAME} · ${java.util.Date()}\n")
                    append("Szál: ${t.name}\n")
                    append("Memória: használt ${(rt.totalMemory() - rt.freeMemory()) / 1048576} MB / max ${rt.maxMemory() / 1048576} MB\n")
                    append("Eszköz: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}\n\n")
                    append(sw.toString())
                }
                File(filesDir, "last_crash.txt").writeText(text)
            }
            previous?.uncaughtException(t, e)
        }
    }

    companion object {
        fun lastCrash(app: Application): String? = File(app.filesDir, "last_crash.txt").takeIf { it.exists() }?.readText()
        fun clearCrash(app: Application) { File(app.filesDir, "last_crash.txt").delete() }
        fun maxHeapMb(): Long = Runtime.getRuntime().maxMemory() / 1048576
    }
}
