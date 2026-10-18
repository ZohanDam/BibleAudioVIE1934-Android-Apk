package com.zohan.nghekinhthanh1925

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class AudioDownloadService : Service() {
    private lateinit var repository: BibleRepository
    private lateinit var store: AudioDownloadStore
    private lateinit var chaptersByOrder: Map<Int, BibleChapter>
    private val executor = Executors.newFixedThreadPool(WORKER_COUNT)
    private val workQueue = ConcurrentLinkedQueue<Int>()
    private val scheduled = ConcurrentHashMap.newKeySet<Int>()
    private val cancelled = ConcurrentHashMap.newKeySet<Int>()
    private val liveWorkers = AtomicInteger(0)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        repository = BibleRepository(applicationContext)
        store = AudioDownloadStore(applicationContext)
        chaptersByOrder = repository.allAudioChapters().associateBy { it.downloadKey }
        store.resetActive()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> cancel(intent.getIntArrayExtra(EXTRA_ORDERS) ?: intArrayOf())
            else -> startDownloads()
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        executor.shutdownNow()
        store.resetActive()
        wakeLock?.takeIf { it.isHeld }?.release()
        repository.close()
        super.onDestroy()
    }

    private fun startDownloads() {
        val orders = store.queuedOrders()
        if (orders.isEmpty()) {
            stopWhenIdle()
            return
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireWakeLock()
        orders.sorted().forEach { order ->
            if (scheduled.add(order)) workQueue.offer(order)
        }
        ensureWorkers()
        updateNotification()
    }

    @Synchronized
    private fun ensureWorkers() {
        if (liveWorkers.get() > 0 || workQueue.isEmpty()) return
        liveWorkers.set(WORKER_COUNT)
        repeat(WORKER_COUNT) {
            executor.execute {
                try {
                    workerLoop()
                } finally {
                    if (liveWorkers.decrementAndGet() == 0) {
                        if (workQueue.isNotEmpty()) ensureWorkers() else stopWhenIdle()
                    }
                }
            }
        }
    }

    private fun workerLoop() {
        while (!Thread.currentThread().isInterrupted) {
            val order = workQueue.poll()
            if (order == null) {
                if (scheduled.isEmpty()) {
                    return
                }
                Thread.sleep(150)
                continue
            }
            if (cancelled.remove(order) || order !in store.queuedOrders()) {
                scheduled.remove(order)
                continue
            }
            val chapter = chaptersByOrder[order]
            if (chapter == null || store.availableFile(chapter) != null) {
                store.finish(order, success = chapter != null)
                scheduled.remove(order)
                updateNotification()
                continue
            }
            store.setActive(order, true)
            updateNotification()
            val success = download(chapter)
            val wasCancelled = cancelled.remove(order) || order !in store.queuedOrders()
            if (wasCancelled) {
                store.discard(order)
            } else {
                store.finish(order, success)
            }
            scheduled.remove(order)
            updateNotification()
        }
    }

    private fun download(chapter: BibleChapter): Boolean {
        val target = store.targetFile(chapter)
        val temporary = File(target.parentFile, "${target.name}.part")
        target.parentFile?.mkdirs()
        repeat(3) { attempt ->
            if (chapter.downloadKey in cancelled || chapter.downloadKey !in store.queuedOrders()) {
                temporary.delete()
                return false
            }
            var connection: HttpURLConnection? = null
            try {
                connection = URL(chapter.url).openConnection() as HttpURLConnection
                connection.connectTimeout = 30_000
                connection.readTimeout = 90_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "NgheKinhThanh1925/2.17")
                if (connection.responseCode !in 200..299) {
                    throw IllegalStateException("HTTP ${connection.responseCode}")
                }
                val contentType = connection.contentType?.substringBefore(';')?.trim()?.lowercase()
                if (contentType != null && !contentType.startsWith("audio/") &&
                    contentType != "application/octet-stream") {
                    throw IllegalStateException("Response is not audio")
                }
                connection.inputStream.use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            if (
                                chapter.downloadKey in cancelled ||
                                Thread.currentThread().isInterrupted
                            ) {
                                throw DownloadCancelled()
                            }
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
                val expectedLength = connection.contentLengthLong
                if (temporary.length() <= 0L ||
                    (expectedLength > 0L && temporary.length() != expectedLength)) {
                    throw IllegalStateException("Incomplete download")
                }
                if (chapter.downloadKey in cancelled || chapter.downloadKey !in store.queuedOrders()) {
                    throw DownloadCancelled()
                }
                if (target.exists()) target.delete()
                if (!temporary.renameTo(target)) {
                    temporary.copyTo(target, overwrite = true)
                    temporary.delete()
                }
                return target.exists() && target.length() > 0L
            } catch (_: DownloadCancelled) {
                temporary.delete()
                return false
            } catch (_: Exception) {
                temporary.delete()
                if (attempt < 2) Thread.sleep(1_000L * (attempt + 1))
            } finally {
                connection?.disconnect()
            }
        }
        return false
    }

    private fun cancel(orders: IntArray) {
        cancelled.addAll(orders.toList())
        val active = store.activeOrders()
        orders.forEach { order ->
            if (order !in active) {
                workQueue.remove(order)
                scheduled.remove(order)
                store.discard(order)
            }
            chaptersByOrder[order]?.let { chapter ->
                File(store.targetFile(chapter).parentFile, "${chapter.filename}.part").delete()
            }
        }
        updateNotification()
        if (scheduled.isEmpty()) stopWhenIdle()
    }

    @Synchronized
    private fun stopWhenIdle() {
        if (scheduled.isNotEmpty() || store.queuedOrders().isNotEmpty()) return
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val manager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = manager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "NgheKinhThanh1925:AudioDownload"
        ).apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1_000L)
        }
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            manager.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: SecurityException) {
        }
    }

    private fun buildNotification(): Notification {
        val summary = store.summary(chaptersByOrder.values)
        val pending = summary.running + summary.queued
        val detail = when {
            pending > 0 -> UiLanguage.text(this,
                "${summary.downloaded} chương đã lưu · ${summary.running}/$WORKER_COUNT đang tải · ${summary.queued} chờ",
                "${summary.downloaded} chapters saved · ${summary.running}/$WORKER_COUNT downloading · ${summary.queued} queued")
            summary.failed > 0 -> UiLanguage.text(this, "Hoàn tất với ${summary.failed} lỗi", "Finished with ${summary.failed} errors")
            else -> UiLanguage.text(this, "Đã tải xong ${summary.downloaded} chương", "Downloaded ${summary.downloaded} chapters")
        }
        val builder = Notification.Builder(this, CHANNEL_ID)
        return builder
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(UiLanguage.text(this, "Đang tải audio Kinh Thánh", "Downloading Bible audio"))
            .setContentText(detail)
            .setOnlyAlertOnce(true)
            .setOngoing(pending > 0)
            .setProgress(summary.downloaded + pending, summary.downloaded, false)
            .setContentIntent(openAppIntent())
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                UiLanguage.text(this, "Tải audio Kinh Thánh", "Bible audio downloads"),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun openAppIntent(): PendingIntent {
        return PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private class DownloadCancelled : RuntimeException()

    companion object {
        const val ACTION_START = "com.zohan.nghekinhthanh1925.DOWNLOAD_START"
        const val ACTION_CANCEL = "com.zohan.nghekinhthanh1925.DOWNLOAD_CANCEL"
        const val EXTRA_ORDERS = "orders"
        const val WORKER_COUNT = 12
        private const val CHANNEL_ID = "bible_downloads"
        private const val NOTIFICATION_ID = 1926
    }
}
