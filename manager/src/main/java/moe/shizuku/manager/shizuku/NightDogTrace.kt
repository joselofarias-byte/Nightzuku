package moe.shizuku.manager.shizuku

import android.content.Context
import androidx.core.content.FileProvider
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NightDogTraceProvider : FileProvider()

/** Local diagnostic journal. Its cadence never wakes the device. */
internal object NightDogTrace {
    private const val TAG = "NightDogTrace"
    const val HEALTHY_RETENTION_MS = 7_200_000L
    private const val SEGMENT_BYTES = 256 * 1024L
    private const val MAX_SEGMENTS = 32
    private const val SEGMENT_MS = 300_000L
    private val session = UUID.randomUUID().toString().substringBefore('-')
    private val dropped = AtomicInteger()
    private val writer = ThreadPoolExecutor(
        1, 1, 30L, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(512),
        { task -> Thread(task, TAG) },
        { _, _ -> dropped.incrementAndGet() }
    ).apply { allowCoreThreadTimeOut(true) }

    fun record(context: Context, detail: String, stable: Boolean = false) {
        val app = context.applicationContext
        val event = stamp(detail)
        writer.execute {
            runCatching { append(app, event, stable) }
                .onFailure { Log.w(TAG, "Error al guardar la traza de NightDog", it) }
        }
    }

    // A locked boot receiver can be the only component keeping the process alive.
    fun boot(context: Context, detail: String) {
        runCatching { append(context, stamp(detail), false) }
            .onFailure { Log.w(TAG, "Error al registrar el arranque", it) }
    }

    fun state(context: Context): String {
        val power = context.getSystemService(PowerManager::class.java)
        val user = context.getSystemService(UserManager::class.java)
        val wifiAdb = runCatching {
            Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", -1)
        }.getOrDefault(-1)
        return "unlocked=${user?.isUserUnlocked} idle=${power?.isDeviceIdleMode} " +
            "interactive=${power?.isInteractive} batteryExempt=" +
            "${power?.isIgnoringBatteryOptimizations(context.packageName)} adbWifi=$wifiAdb"
    }

    @Synchronized
    fun export(context: Context): File? {
        val source = segments(directory(context))
        if (source.isEmpty()) return null
        val outputDir = File(context.cacheDir, "nightdog-export").apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - HEALTHY_RETENTION_MS
        outputDir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        val output = File.createTempFile("NightDog-", ".txt", outputDir)
        try {
            output.outputStream().buffered().use { stream ->
                for (file in source) file.inputStream().use { it.copyTo(stream) }
            }
            return output
        } catch (error: Exception) {
            output.delete()
            throw error
        }
    }

    private fun stamp(detail: String): String {
        val safe = detail.replace('\n', ' ').replace('\r', ' ').take(1_200)
        val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        return "$utc elapsedMs=${SystemClock.elapsedRealtime()} " +
            "uptimeMs=${SystemClock.uptimeMillis()} pid=${Process.myPid()} session=$session $safe\n"
    }

    private fun directory(context: Context) =
        File(context.createDeviceProtectedStorageContext().filesDir, "nightdog-trace")

    private fun segments(dir: File): List<File> =
        dir.listFiles()?.filter { it.name.endsWith(".log") && it.nameWithoutExtension.toLongOrNull() != null }
            ?.sortedBy { it.nameWithoutExtension.toLong() }.orEmpty()

    @Synchronized
    private fun append(context: Context, event: String, stable: Boolean) {
        val dir = directory(context)
        dir.mkdirs()
        val now = System.currentTimeMillis()
        var files = segments(dir)
        val latest = files.lastOrNull()
        val lost = dropped.getAndSet(0)
        val line = if (lost == 0) event else stamp("TRACE_QUEUE dropped=$lost") + event
        val bytes = line.toByteArray(Charsets.UTF_8)
        // Rotate on wall-clock buckets or size. File names preserve write order across reboots.
        val newestBucket = latest?.lastModified()?.div(SEGMENT_MS)
        val target = if (latest == null || newestBucket != now / SEGMENT_MS ||
            latest.length() + bytes.size > SEGMENT_BYTES
        ) {
            val index = (latest?.nameWithoutExtension?.toLong() ?: 0L) + 1
            File(dir, "$index.log")
        } else latest
        target.appendBytes(bytes)
        files = segments(dir)
        if (stable) {
            for (file in files) {
                if (file != target && file.lastModified() < now - HEALTHY_RETENTION_MS) file.delete()
            }
            files = segments(dir)
        }
        files.dropLast(MAX_SEGMENTS).forEach { it.delete() }
    }
}
