package hu.novaprocess.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import hu.novaprocess.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** A futó feldolgozás állapota – a ViewModel írja, a szolgáltatás mutatja értesítésben. */
object ProcessingBus {
    data class Status(val title: String, val text: String, val percent: Int, val done: Boolean = false, val failed: Boolean = false)
    val status = MutableStateFlow<Status?>(null)

    fun begin(ctx: Context, title: String) {
        status.value = Status(title, "Indítás…", 0)
        val i = Intent(ctx, ProcessingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
    }
    fun progress(title: String, text: String, percent: Int) { status.value = Status(title, text, percent.coerceIn(0, 100)) }
    fun done(title: String, text: String) { status.value = Status(title, text, 100, done = true) }
    fun failed(title: String, text: String) { status.value = Status(title, text, 0, done = true, failed = true) }
}

/**
 * Előtérbeli szolgáltatás: életben tartja az appot (és ébren a CPU-t) a stack / pipeline alatt, amíg a
 * felhasználó máshol jár; a végén „kész” értesítést ad, amire koppintva az app megnyílik.
 */
class ProcessingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels()
        val first = ProcessingBus.status.value ?: ProcessingBus.Status("NovaProcess", "Feldolgozás…", 0)
        startForeground(NOTIF_ID, buildProgress(first))
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NovaProcess:stack").apply { acquire(4 * 60 * 60 * 1000L) }
        }
        job?.cancel()
        job = scope.launch {
            ProcessingBus.status.collect { st ->
                if (st == null) return@collect
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (!st.done) nm.notify(NOTIF_ID, buildProgress(st))
                else {
                    nm.notify(DONE_ID, buildDone(st))
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    ProcessingBus.status.value = null
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }; wakeLock = null
        super.onDestroy()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildProgress(st: ProcessingBus.Status): Notification =
        NotificationCompat.Builder(this, CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(st.title).setContentText(st.text)
            .setProgress(100, st.percent, st.percent == 0)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setContentIntent(openAppIntent())
            .build()

    private fun buildDone(st: ProcessingBus.Status): Notification =
        NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(if (st.failed) android.R.drawable.stat_notify_error else android.R.drawable.stat_sys_download_done)
            .setContentTitle(st.title).setContentText(st.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(st.text))
            .setAutoCancel(true).setContentIntent(openAppIntent())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "Feldolgozás folyamatban", NotificationManager.IMPORTANCE_LOW).apply { description = "Stackelés és pipeline állapota" })
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Feldolgozás kész", NotificationManager.IMPORTANCE_HIGH).apply { description = "Értesítés, ha a stack vagy a pipeline elkészült" })
    }

    companion object { const val CH_PROGRESS = "nova_progress"; const val CH_DONE = "nova_done"; const val NOTIF_ID = 1001; const val DONE_ID = 1002 }
}
