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

    init {
        // v0.4.0 曾下載錯誤的 GPU-specific bundle。它缺少目前 LiteRT-LM
        // Engine 所需的 TF_LITE_PREFILL_DECODE signature，因此不能直接使用。
        // 新版啟動時先清掉舊檔，避免佔用約 3 GB 空間。
        cleanupLegacyModel()
    }

    fun installedModelPath(): String? {
        cleanupLegacyModel()
        val file = modelFile()
        return if (
            prefs.getBoolean(KEY_VERIFIED, false) &&
            prefs.getString(KEY_MODEL_VERSION, null) == MODEL_VERSION &&
            file.exists() &&
            file.length() == MODEL_SIZE_BYTES
        ) {
            file.absolutePath
        } else {
            null
        }
    }

    fun activeDownloadId(): Long? {
        val id = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        return if (id > 0L) id else null
    }

    fun startDownload(): Long {
        cleanupLegacyModel()

        val target = modelFile()
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()

        val storageRoot = target.parentFile ?: target
        val freeBytes = StatFs(storageRoot.absolutePath).availableBytes
        require(freeBytes >= MIN_FREE_BYTES) {
            "儲存空間不足。Gemma 4 E4B 約 3.66 GB，建議至少保留 5 GB 可用空間。"
        }

        activeDownloadId()?.let { oldId ->
            runCatching { downloadManager.remove(oldId) }
        }

        prefs.edit()
            .putBoolean(KEY_VERIFIED, false)
            .remove(KEY_MODEL_VERSION)
            .apply()

        val request = DownloadManager.Request(Uri.parse(MODEL_URL))
            .setTitle("LocalPilot AI 模型")
            .setDescription("正在安裝 Gemma 4 E4B · 約 3.66 GB")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverRoaming(false)
            .setAllowedOverMetered(true)
            .setDestinationInExternalFilesDir(context, null, "models/$MODEL_FILE_NAME")
            .addRequestHeader("User-Agent", "LocalPilot/0.4.1")

        val id = downloadManager.enqueue(request)
        prefs.edit().putLong(KEY_DOWNLOAD_ID, id).apply()
        return id
    }

    fun cancel() {
        activeDownloadId()?.let { id -> runCatching { downloadManager.remove(id) } }
        prefs.edit()
            .remove(KEY_DOWNLOAD_ID)
            .remove(KEY_MODEL_VERSION)
            .putBoolean(KEY_VERIFIED, false)
            .apply()
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
                    require(file.length() == MODEL_SIZE_BYTES) {
                        "模型大小不正確：${file.length()} bytes，預期 $MODEL_SIZE_BYTES bytes。請重新下載。"
                    }

                    onProgress(
                        ModelInstallProgress(
                            status = STATUS_VERIFYING,
                            percent = 100,
                            downloadedBytes = file.length(),
                            totalBytes = file.length(),
                            detail = "正在驗證模型完整性…"
                        )
                    )

                    require(verifySha256(file)) {
                        "模型 SHA-256 驗證失敗，已停止載入。請重新下載。"
                    }

                    prefs.edit()
                        .putBoolean(KEY_VERIFIED, true)
                        .putString(KEY_MODEL_VERSION, MODEL_VERSION)
                        .remove(KEY_DOWNLOAD_ID)
                        .apply()
                    return file
                }

                STATUS_FAILED -> throw IllegalStateException(
                    progress.detail.ifBlank { "模型下載失敗。" }
                )
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
            val percent = if (total > 0L) {
                ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
            } else {
                0
            }

            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL ->
                    ModelInstallProgress(STATUS_SUCCESS, 100, downloaded, total)

                DownloadManager.STATUS_FAILED -> {
                    val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    ModelInstallProgress(
                        STATUS_FAILED,
                        percent,
                        downloaded,
                        total,
                        "下載失敗，錯誤碼：$reason"
                    )
                }

                DownloadManager.STATUS_PAUSED ->
                    ModelInstallProgress(
                        STATUS_PAUSED,
                        percent,
                        downloaded,
                        total,
                        "下載暫停，系統會自動重試。"
                    )

                DownloadManager.STATUS_PENDING ->
                    ModelInstallProgress(
                        STATUS_PENDING,
                        percent,
                        downloaded,
                        total,
                        "等待開始下載…"
                    )

                else ->
                    ModelInstallProgress(
                        STATUS_DOWNLOADING,
                        percent,
                        downloaded,
                        total,
                        "正在下載 AI 模型…"
                    )
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
        digest.digest()
            .joinToString("") { "%02x".format(it) }
            .equals(MODEL_SHA256, ignoreCase = true)
    }

    private fun modelFile(): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "models/$MODEL_FILE_NAME")
    }

    private fun cleanupLegacyModel() {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val legacy = File(base, "models/$LEGACY_MODEL_FILE_NAME")
        if (legacy.exists()) {
            legacy.delete()
            prefs.edit()
                .putBoolean(KEY_VERIFIED, false)
                .remove(KEY_MODEL_VERSION)
                .remove(KEY_DOWNLOAD_ID)
                .apply()
        }
    }

    companion object {
        const val MODEL_NAME = "Gemma 4 E4B"
        const val MODEL_FILE_NAME = "gemma-4-E4B-it.litertlm"

        // Pin to the exact LiteRT Community revision used by the current Android
        // model allowlist. This prevents a future upstream file replacement from
        // silently breaking LocalPilot's SHA verification/runtime compatibility.
        const val MODEL_VERSION = "28299f30ee4d43294517a4ac93abd6163412f07f"
        const val MODEL_SIZE_BYTES = 3_659_530_240L
        const val MODEL_SHA256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0"
        const val MODEL_URL = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/$MODEL_VERSION/gemma-4-E4B-it.litertlm?download=true"

        private const val LEGACY_MODEL_FILE_NAME = "gemma-4-E4B-it-gpu.litertlm"

        const val STATUS_PENDING = "pending"
        const val STATUS_DOWNLOADING = "downloading"
        const val STATUS_PAUSED = "paused"
        const val STATUS_VERIFYING = "verifying"
        const val STATUS_SUCCESS = "success"
        const val STATUS_FAILED = "failed"

        private const val KEY_DOWNLOAD_ID = "download_id"
        private const val KEY_VERIFIED = "verified"
        private const val KEY_MODEL_VERSION = "model_version"
        private const val MIN_FREE_BYTES = 5_000_000_000L
    }
}
