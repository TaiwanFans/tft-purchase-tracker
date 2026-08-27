package com.tft.purchase

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.PointF
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.model.OCRResult
import com.paddle.ocr.util.OpenCVUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Locale

/**
 * Primary OCR bridge backed by PP-OCRv6 Medium through PaddleOCR's official Android SDK.
 * V2.0.14 prioritizes dense A4 purchase-order text accuracy over minimum model size.
 * Box coordinates and confidence are preserved for downstream table/field reasoning.
 */
object PaddleOcrBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engineMutex = Mutex()

    @Volatile
    private var cached: PaddleOCR? = null

    @JvmStatic
    fun recognize(context: Context, imagePath: String, callback: PaddleOcrCallback) {
        val app = context.applicationContext
        scope.launch {
            try {
                val file = File(imagePath)
                if (!file.isFile || file.length() <= 0L) throw IllegalArgumentException("PP-OCR 找不到處理後圖片")
                val engine = getEngine(app)
                val bytes = file.readBytes()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(imagePath, bounds)
                val width = bounds.outWidth.coerceAtLeast(1)
                val height = bounds.outHeight.coerceAtLeast(1)

                val result = engine.recognize(bytes)
                val items = result.results.sortedWith(compareBy<OCRResult>(
                    { centerY(it) }, { centerX(it) }
                ))

                val sb = StringBuilder()
                sb.append("[PP-OCRv6_MEDIUM]\n")
                sb.append("engine=onnxruntime; lines=").append(items.size)
                    .append("; det_ms=").append(result.detectionTimeMs)
                    .append("; rec_ms=").append(result.recognitionTimeMs).append('\n')

                var kept = 0
                for (item in items) {
                    val text = item.text.trim()
                    if (text.isEmpty()) continue
                    val b = normalizedBox(item, width, height)
                    // Keep moderately uncertain text because a second OCR and the image will cross-check it.
                    if (item.confidence < 0.14f) continue
                    sb.append("[region=").append(region(b.cx, b.cy))
                        .append(" x=").append(f(b.x))
                        .append(" y=").append(f(b.y))
                        .append(" w=").append(f(b.w))
                        .append(" h=").append(f(b.h))
                        .append(" conf=").append(String.format(Locale.US, "%.2f", item.confidence))
                        .append("] ").append(text.replace('\n', ' ')).append('\n')
                    kept++
                    if (kept >= 320) break
                }
                callback.onSuccess(sb.toString())
            } catch (t: Throwable) {
                callback.onFailure(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    private suspend fun getEngine(context: Context): PaddleOCR {
        cached?.let { return it }
        return engineMutex.withLock {
            cached?.let { return@withLock it }
            if (!OpenCVUtils.init(context)) throw IllegalStateException("PP-OCR 無法初始化 OpenCV")
            val config = PaddleOCRConfig(
                // Dense A4 forms contain tiny characters. A larger detector input keeps more glyph detail.
                detLimitSideLen = 1280,
                detLimitType = "min",
                detMaxSideLimit = 3600,
                detThresh = 0.18f,
                detBoxThresh = 0.38f,
                detUnclipRatio = 1.42f,
                detMaxCandidates = 3500,
                detUseDilation = false,
                detScoreMode = "slow",
                detBoxType = "quad",
                recScoreThresh = 0.14f,
                recBatchSize = 4,
            )
            val created = PaddleOCR.create(
                context = context,
                config = config,
                engineConfig = EngineConfig(numThreads = 6),
                detModelAssetPath = "models/det/inference.onnx",
                recModelAssetPath = "models/rec/inference.onnx",
                recConfigAssetPath = "models/rec/inference.yml",
            )
            cached = created
            created
        }
    }

    private data class NBox(
        val x: Float, val y: Float, val w: Float, val h: Float,
        val cx: Float, val cy: Float,
    )

    private fun normalizedBox(item: OCRResult, imageW: Int, imageH: Int): NBox {
        val points = item.box.points
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p: PointF in points) {
            minX = minOf(minX, p.x); minY = minOf(minY, p.y)
            maxX = maxOf(maxX, p.x); maxY = maxOf(maxY, p.y)
        }
        val x = (minX / imageW).coerceIn(0f, 1f)
        val y = (minY / imageH).coerceIn(0f, 1f)
        val w = ((maxX - minX) / imageW).coerceIn(0f, 1f)
        val h = ((maxY - minY) / imageH).coerceIn(0f, 1f)
        return NBox(x, y, w, h, (x + w / 2f).coerceIn(0f, 1f), (y + h / 2f).coerceIn(0f, 1f))
    }

    private fun centerX(item: OCRResult): Float = item.box.points.map { it.x }.average().toFloat()
    private fun centerY(item: OCRResult): Float = item.box.points.map { it.y }.average().toFloat()

    private fun region(x: Float, y: Float): String = when {
        y < 0.30f && x < 0.55f -> "HEADER_LEFT"
        y < 0.30f -> "HEADER_RIGHT"
        y < 0.78f -> "ITEM_TABLE"
        else -> "FOOTER"
    }

    private fun f(v: Float): String = String.format(Locale.US, "%.3f", v)
}
