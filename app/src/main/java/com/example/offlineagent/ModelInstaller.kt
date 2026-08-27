package com.example.offlineagent

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest


data class ModelInstallProgress(
    val status: String,
    val percent: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val detail: String = ""
)

class ModelInstaller(
    private val context: Context
) {
    private val prefs = context.getSharedPreferences("model_installer", Context.MODE_PRIVATE)
    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    fun installedModelPath(): String? {
        val file = modelFile()
        return if (prefs.getBoolean(KEY_VERIFIED, false) && file.exists()) file.absolutePath else null
    }

    fun activeDownloadId(): Long? {
        val id = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        return if (id > 0L) id else null
    }

    fun startDownload(): Long {
        val target = modelFile()
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()

        val storageRoot = target.parentFile ?: target
        val freeBytes = StatFs(storageRoot.absolutePath).availableBytes
        require(freeBytes >= MIN_FREE_BYTES) {
            "儲存空間不足。Gemma 4 E4B 約 3 GB，建議至少保留 4 GB 可用空間。"
        }

        prefs.edit().putBoolean(KEY_VERIFIED, false).apply()

        val request = DownloadManager.Request(Uri.parse(MODEL_URL))
            .setTitle("LocalPilot AI 模型")
            .setDescription("正在安裝 Gemma 4 E4B · 約 3 GB")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverRoaming(false)
            .setAllowedOverMetered(true)
            .setDestinationInExternalFilesDir(context, null, "models/$MODEL_FILE_NAME")
            .addRequestHeader("User-Agent", "LocalPilot/0.4")

        val id = downloadManager.enqueue(request)
        prefs.edit().putLong(KEY_DOWNLOAD_ID, id).apply()
        return id
    }

    fun cancel() {
        activeDownloadId()?.let { id -> downloadManager.remove(id) }
        prefs.edit().remove(KEY_DOWNLOAD_ID).putBoolean(KEY_VERIFIED, false).apply()
        modelFile().delete()
    }

    suspend fun waitUntilReady(
        downloadId: Long,
        onProgress: (ModelInstallProgress) -> Unit
    ): File {
        while (true) {
            val progress = query(downloadId)
            onProgress(progress)

            when (progress.status) {
                STATUS_SUCCESS -> {
                    val file = modelFile()
                    require(file.exists() && file.length() > 0L) { "下載完成但找不到模型檔。" }
                    onProgress(
                        ModelInstallProgress(
                            status = STATUS_VERIFYING,
                            percent = 100,
                            downloadedBytes = file.length(),
                            totalBytes = file.length(),
                            detail = "正在驗證模型完整性…"
                        )
                    )
                    require(verifySha256(file)) { "模型檔驗證失敗，請重新下載。" }
                    prefs.edit()
                        .putBoolean(KEY_VERIFIED, true)
                        .remove(KEY_DOWNLOAD_ID)
                        .apply()
                    return file
                }
                STATUS_FAILED -> throw IllegalStateException(progress.detail.ifBlank { "模型下載失敗。" })
            }

            delay(1000)
        }
    }

    private fun query(downloadId: Long): ModelInstallProgress {
        val query = DownloadManager.Query().setFilterById(downloadId)
        downloadManager.query(query).use { cursor ->
            if (!cursor.moveToFirst()) {
                return ModelInstallProgress(STATUS_FAILED, 0, 0L, 0L, "找不到下載任務。")
            }

            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val percent = if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else 0

            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> ModelInstallProgress(STATUS_SUCCESS, 100, downloaded, total)
                DownloadManager.STATUS_FAILED -> {
                    val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    ModelInstallProgress(STATUS_FAILED, percent, downloaded, total, "下載失敗，錯誤碼：$reason")
                }
                DownloadManager.STATUS_PAUSED -> ModelInstallProgress(STATUS_PAUSED, percent, downloaded, total, "下載暫停，系統會自動重試。")
                DownloadManager.STATUS_PENDING -> ModelInstallProgress(STATUS_PENDING, percent, downloaded, total, "等待開始下載…")
                else -> ModelInstallProgress(STATUS_DOWNLOADING, percent, downloaded, total, "正在下載 AI 模型…")
            }
        }
    }

    private suspend fun verifySha256(file: File): Boolean = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered(8 * 1024 * 1024).use { input ->
            val buffer = ByteArray(8 * 1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }.equals(MODEL_SHA256, ignoreCase = true)
    }

    private fun modelFile(): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "models/$MODEL_FILE_NAME")
    }

    companion object {
        const val MODEL_NAME = "Gemma 4 E4B GPU"
        const val MODEL_FILE_NAME = "gemma-4-E4B-it-gpu.litertlm"
        const val MODEL_SHA256 = "4912bb5a9c30993c51a7711f763212077458529312175df0573a78323a2bb7ff"
        const val MODEL_URL = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it-gpu.litertlm?download=true"

        const val STATUS_PENDING = "pending"
        const val STATUS_DOWNLOADING = "downloading"
        const val STATUS_PAUSED = "paused"
        const val STATUS_VERIFYING = "verifying"
        const val STATUS_SUCCESS = "success"
        const val STATUS_FAILED = "failed"

        private const val KEY_DOWNLOAD_ID = "download_id"
        private const val KEY_VERIFIED = "verified"
        private const val MIN_FREE_BYTES = 4_000_000_000L
    }
}
