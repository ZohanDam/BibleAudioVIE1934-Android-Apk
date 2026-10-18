package com.zohan.nghekinhthanh1925

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.Closeable
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean

class AppUpdateController(
    private val activity: Activity,
    private val client: AppUpdateClient = AppUpdateClient(activity.applicationContext),
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
) : Closeable {
    private val handler = Handler(Looper.getMainLooper())
    private var closed = false
    private var busy = false
    private var dialog: AlertDialog? = null
    private var cancellation = AtomicBoolean(false)
    private var waitingForPermission = false
    private var pendingRelease: AppRelease? = null

    fun check() {
        if (busy) return
        begin("Đang kiểm tra cập nhật…")
        val token = cancellation
        worker.execute {
            try {
                val release = client.latest()
                result(token) {
                    val installed = client.currentVersion()
                    when {
                        release == null -> message("Chưa có bản phát hành trên GitHub.")
                        release.versionCode <= AppUpdateClient.versionCode(installed) ->
                            message("Đang dùng phiên bản mới nhất (${installed.versionName}).")
                        release.minimumSdk > Build.VERSION.SDK_INT ->
                            message("Bản cập nhật yêu cầu Android API ${release.minimumSdk} trở lên.")
                        else -> offer(release)
                    }
                }
            } catch (error: Exception) {
                failed(token, error)
            }
        }
    }

    private fun offer(release: AppRelease) {
        val sizeMb = release.sizeBytes / 1_000_000.0
        dialog = AlertDialog.Builder(activity)
            .setTitle("Cập nhật ${release.versionName}")
            .setMessage("Dung lượng: %.1f MB\n\n".format(sizeMb) + release.notes +
                "\n\nBản cập nhật giữ nguyên audio đã tải và cài đặt. Android sẽ yêu cầu xác nhận cài đặt.")
            .setPositiveButton("Tải cập nhật") { _, _ -> download(release) }
            .setNegativeButton("Để sau", null)
            .show()
    }

    private fun download(release: AppRelease) {
        if (busy) return
        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val label = TextView(activity).apply { text = "0%" }
        val padding = (24 * activity.resources.displayMetrics.density).toInt()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, padding / 2)
            addView(progress)
            addView(label)
        }
        begin("Đang tải bản ${release.versionName}", content)
        val token = cancellation
        worker.execute {
            try {
                client.download(release, { token.get() }) { percent ->
                    handler.post {
                        if (!closed && !token.get()) {
                            progress.progress = percent
                            label.text = "$percent%"
                        }
                    }
                }
                result(token) {
                    pendingRelease = release
                    requestInstall()
                }
            } catch (error: Exception) {
                failed(token, error)
            }
        }
    }

    private fun requestInstall() {
        if (pendingRelease == null || closed) return
        if (!activity.packageManager.canRequestPackageInstalls()) {
            dialog = AlertDialog.Builder(activity)
                .setTitle("Cho phép cài đặt cập nhật")
                .setMessage("Bật “Cho phép từ nguồn này” cho ứng dụng, rồi quay lại để cài đặt bản cập nhật.")
                .setPositiveButton("Mở cài đặt") { _, _ ->
                    try {
                        waitingForPermission = true
                        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}")))
                    } catch (_: Exception) {
                        waitingForPermission = false
                        message("Không thể mở cài đặt quyền cài ứng dụng.")
                    }
                }
                .setNegativeButton("Để sau", null)
                .show()
            return
        }
        install()
    }

    fun onResume() {
        if (!waitingForPermission) return
        waitingForPermission = false
        if (activity.packageManager.canRequestPackageInstalls()) install()
        else message("Chưa cấp quyền cài cập nhật. Có thể thử lại từ menu cập nhật.")
    }

    private fun install() {
        val release = pendingRelease ?: return
        begin("Đang xác minh bản cập nhật…")
        val token = cancellation
        worker.execute {
            try {
                client.verifyArchive(java.io.File(activity.cacheDir, "app-updates/update.apk"), release)
                result(token) {
                    try {
                        activity.startActivity(Intent(Intent.ACTION_VIEW)
                            .setDataAndType(UpdateApkProvider.uri(activity), UpdateApkProvider.APK_MIME)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        pendingRelease = null
                    } catch (_: Exception) {
                        message("Không thể mở trình cài đặt Android.")
                    }
                }
            } catch (error: Exception) {
                failed(token, error)
            }
        }
    }

    private fun begin(title: String, view: android.view.View? = null) {
        busy = true
        cancellation = AtomicBoolean(false)
        dialog?.dismiss()
        dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setNegativeButton("Hủy") { _, _ -> cancel() }
            .apply { if (view != null) setView(view) }
            .setOnCancelListener { cancel() }
            .show()
    }

    private fun cancel() {
        cancellation.set(true)
        // The worker owns its temporary file until the network operation finishes.
        // Keep busy set so a cancelled request cannot overlap a new download.
    }

    private fun result(token: AtomicBoolean, action: () -> Unit) {
        handler.post {
            if (closed) return@post
            busy = false
            dialog?.dismiss()
            dialog = null
            if (!token.get() && !activity.isFinishing) action()
        }
    }

    private fun failed(token: AtomicBoolean, error: Exception) {
        result(token) {
            if (error !is CancellationException) message("Không thể cập nhật. Kiểm tra kết nối hoặc thử lại sau.\n\n${error.message.orEmpty().take(200)}")
        }
    }

    private fun message(text: String) {
        if (closed || activity.isFinishing) return
        dialog = AlertDialog.Builder(activity).setTitle("Cập nhật ứng dụng")
            .setMessage(text).setPositiveButton("Đóng", null).show()
    }

    override fun close() {
        closed = true
        cancellation.set(true)
        dialog?.dismiss()
        dialog = null
        handler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
    }
}
