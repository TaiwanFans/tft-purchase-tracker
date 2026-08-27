package com.tft.purchase

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Handler
import android.os.Looper
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.SamplerConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max

/**
 * V2.0.11 fast-path Gemma 4 E4B recognizer.
 *
 * Normal documents use only two vision passes:
 *  1) full-width header (vendor/address/order no/purchase date)
 *  2) item table
 * A third repair pass runs only when deterministic checks find missing or contradictory data.
 *
 * GPU initialization fallback is separated from inference errors so a mid-inference exception no
 * longer causes the entire document to be repeated from the beginning on slow CPU.
 */
object GemmaBridge {
    private val lock = Any()
    @Volatile private var engine: Engine? = null
    @Volatile private var engineModelPath: String? = null
    private val main = Handler(Looper.getMainLooper())

    @JvmStatic
    fun isReady(context: Context): Boolean = GemmaModelManager.isReady(context)

    @JvmStatic
    fun warmUpAsync(context: Context) {
        val app = context.applicationContext
        if (!GemmaModelManager.isReady(app)) return
        if (engine?.isInitialized() == true) return
        Thread {
            try { obtainGpuEngine(app, GemmaModelManager.modelFile(app)) } catch (_: Throwable) {}
        }.start()
    }

    @JvmStatic
    fun analyze(context: Context, imagePath: String, ocrText: String, callback: GemmaAiCallback) {
        analyzeAdvanced(context, imagePath, null, callback)
    }

    @JvmStatic
    fun analyzeAdvanced(
        context: Context,
        imagePath: String,
        progress: GemmaProgressCallback?,
        callback: GemmaAiCallback
    ) {
        val app = context.applicationContext
        Thread {
            var temporaryCpu: Engine? = null
            try {
                if (!GemmaModelManager.isReady(app)) {
                    throw IllegalStateException("Gemma 4 E4B 尚未完整下載並驗證")
                }
                val model = GemmaModelManager.modelFile(app)
                notifyProgress(progress, 3, "準備 Gemma 4 E4B 高速模式")

                val selected: Engine
                val gpu = try {
                    obtainGpuEngine(app, model)
                } catch (_: Throwable) {
                    null
                }

                if (gpu != null) {
                    selected = gpu
                    notifyProgress(progress, 8, "GPU 高速引擎已就緒")
                } else {
                    notifyProgress(progress, 8, "GPU 無法初始化，使用 CPU 相容模式（較慢）")
                    temporaryCpu = createCpuEngine(app, model)
                    selected = temporaryCpu!!
                }

                // Important: inference errors do NOT restart the whole document on CPU.
                val response = runFast(selected, app, imagePath, progress)
                main.post { callback.onSuccess(response) }
            } catch (error: Throwable) {
                val msg = "Gemma 4 E4B 分析失敗：" +
                    (error.message ?: error.javaClass.simpleName)
                main.post { callback.onFailure(msg) }
            } finally {
                try { temporaryCpu?.close() } catch (_: Throwable) {}
            }
        }.start()
    }

    private fun runFast(
        e: Engine,
        context: Context,
        imagePath: String,
        progress: GemmaProgressCallback?
    ): String {
        val prepared = prepareImages(context, imagePath)
        try {
            notifyProgress(progress, 18, "第 1/2 階段：辨識廠商、單號與採購日期")
            val headerRaw = send(
                e,
                prepared.header.absolutePath,
                headerPrompt(),
                "你是固定格式台灣採購單表頭辨識器。只抄圖片可見資料，不推測、不改寫。",
                450
            )

            notifyProgress(progress, 50, "第 2/2 階段：辨識品項、數量與交貨日")
            val tableRaw = send(
                e,
                prepared.table.absolutePath,
                tablePrompt(),
                "你是固定格式採購單表格辨識器。只讀正式品項列，每個數字都要依欄位位置確認。",
                1300
            )

            val focused = mergePasses(headerRaw, tableRaw)
            notifyProgress(progress, 82, "程式快速檢查單號、日期、數量與金額")

            if (!needsRepair(focused)) {
                notifyProgress(progress, 96, "快速辨識完成，不需要第二次稽核")
                return focused.toString()
            }

            // Only difficult documents pay the cost of a third vision pass.
            notifyProgress(progress, 84, "偵測到疑問欄位，AI 只補強一次")
            val repairRaw = send(
                e,
                prepared.verify.absolutePath,
                repairPrompt(focused.toString()),
                "你是採購單資料修復器。圖片是唯一真實來源；只修正缺漏或矛盾欄位，禁止創造資料。",
                1300
            )
            val repaired = extractJson(repairRaw)
            notifyProgress(progress, 96, "補強完成並合併可信資料")
            return if (repaired != null && hasUsefulData(repaired)) {
                mergeRepair(focused, repaired).toString()
            } else {
                focused.toString()
            }
        } finally {
            prepared.cleanup()
        }
    }

    private fun send(e: Engine, imagePath: String, prompt: String, system: String, maxTokens: Int): String {
        val config = ConversationConfig(
            systemInstruction = Contents.of(system),
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 31),
            maxOutputToken = maxTokens
        )
        return e.createConversation(config).use { conv ->
            conv.sendMessage(
                Contents.of(
                    Content.ImageFile(imagePath),
                    Content.Text(prompt)
                )
            ).toString()
        }
    }

    private fun mergePasses(headerRaw: String, tableRaw: String): JSONObject {
        val header = extractJson(headerRaw)
        val table = extractJson(tableRaw)
        val out = JSONObject()
        out.put("vendor", header?.optString("vendor", "") ?: "")
        out.put("location", header?.optString("location", "") ?: "")
        out.put("order_no", header?.optString("order_no", "") ?: "")
        out.put("purchase_date", header?.optString("purchase_date", "") ?: "")
        out.put("items", table?.optJSONArray("items") ?: JSONArray())
        return out
    }

    private fun mergeRepair(focused: JSONObject, repaired: JSONObject): JSONObject {
        val out = JSONObject()
        for (key in arrayOf("vendor", "location", "order_no", "purchase_date")) {
            val repairValue = repaired.optString(key, "").trim()
            val baseValue = focused.optString(key, "").trim()
            out.put(key, if (repairValue.isNotEmpty()) repairValue else baseValue)
        }
        val repairedItems = repaired.optJSONArray("items")
        val focusedItems = focused.optJSONArray("items") ?: JSONArray()
        out.put("items", if (repairedItems != null && repairedItems.length() > 0) repairedItems else focusedItems)
        return out
    }

    private fun needsRepair(o: JSONObject): Boolean {
        if (o.optString("vendor", "").isBlank()) return true
        if (o.optString("order_no", "").isBlank()) return true
        if (o.optString("purchase_date", "").isBlank()) return true

        val items = o.optJSONArray("items") ?: return true
        if (items.length() == 0) return true

        val orderDigits = o.optString("order_no", "").filter { it.isDigit() }
        val dateDigits = o.optString("purchase_date", "").filter { it.isDigit() }
        if (orderDigits.length >= 8 && dateDigits.length == 8 && orderDigits.substring(0, 8) != dateDigits) {
            return true
        }

        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: return true
            if (item.optString("description", "").isBlank()) return true
            if (item.optString("quantity", "").isBlank()) return true
            if (item.optString("delivery_date", "").isBlank()) return true

            val q = parseNumber(item.optString("quantity", ""))
            val unitPrice = parseNumber(item.optString("unit_price", ""))
            val subtotal = parseNumber(item.optString("subtotal", ""))
            if (q != null && unitPrice != null && subtotal != null && subtotal != 0.0) {
                val expected = q * unitPrice
                val tolerance = max(1.0, abs(subtotal) * 0.015)
                if (abs(expected - subtotal) > tolerance) return true
            }
        }
        return false
    }

    private fun parseNumber(value: String?): Double? {
        if (value.isNullOrBlank()) return null
        val cleaned = value.replace(",", "").replace(Regex("[^0-9.\\-]"), "")
        if (cleaned.isBlank() || cleaned == "-" || cleaned == ".") return null
        return cleaned.toDoubleOrNull()
    }

    private fun extractJson(raw: String?): JSONObject? {
        if (raw.isNullOrBlank()) return null
        val a = raw.indexOf('{')
        val b = raw.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return try { JSONObject(raw.substring(a, b + 1)) } catch (_: Throwable) { null }
    }

    private fun hasUsefulData(o: JSONObject): Boolean {
        if (o.optString("vendor", "").isNotBlank()) return true
        if (o.optString("order_no", "").isNotBlank()) return true
        if (o.optString("purchase_date", "").isNotBlank()) return true
        return (o.optJSONArray("items")?.length() ?: 0) > 0
    }

    private fun headerPrompt(): String = """
這是採購單上方表頭的放大圖片。只輸出 JSON，不要說明：
{"vendor":"","location":"","order_no":"","purchase_date":""}

規則：
- vendor：只取「供應廠商」右側內容，括號代碼保留，不要取本公司名稱。
- location：只取「廠商地址」右側內容。
- order_no：只取右上「採購單號」完整數字，通常為 12 碼 YYYYMMDD+4碼流水號。
- purchase_date：只取右上「採購日期」，輸出 YYYY-MM-DD。
- 不要把電話、聯絡人、交貨日期當成上述欄位。
- 看不清楚就留空，禁止猜字或補數字。
""".trimIndent()

    private fun tablePrompt(): String = """
這是採購單正式品項表格的放大圖片。只輸出 JSON，不要說明：
{"items":[{"line_no":"001","description":"","quantity":"","unit":"","unit_price":"","subtotal":"","delivery_date":"","note":""}]}

只讀「項次／品名規格明細／數量／單位／單價／小計／交貨日期／備註」正式資料列。
規則：
- 只有左欄真的看到 001、002、003… 才能建立品項。
- 「以下空白」、本頁小計、稅額、總計、FAXED、印章、頁尾條款全部排除。
- description 只取同一列品名規格；同列換行可合併，不可跨列。
- quantity 必須逐位完整抄寫，200 不可變成 2，1200 不可變成 12。
- unit、unit_price、subtotal、delivery_date、note 必須取同一水平列。
- delivery_date 輸出 YYYY-MM-DD。
- 看不清楚欄位留空，不要使用相鄰列猜補。
""".trimIndent()

    private fun repairPrompt(candidate: String): String = """
這是前兩次快速辨識的候選資料：
$candidate

程式發現候選中至少一個欄位缺漏或互相矛盾。請對照圖片，只修正真正有問題的地方，輸出完整 JSON：
{"vendor":"","location":"","order_no":"","purchase_date":"","items":[{"line_no":"001","description":"","quantity":"","unit":"","unit_price":"","subtotal":"","delivery_date":"","note":""}]}

檢查重點：
1. 採購單號只能來自右上「採購單號」；12 碼時前 8 碼通常應與採購日期 YYYYMMDD 一致。
2. 數量必須完整保留位數。
3. quantity × unit_price 與 subtotal 若明顯不符，重新看該列數字。
4. delivery_date 只能來自同一列交貨日期欄。
5. 「以下空白」及頁尾說明永遠不是品項。
6. 圖片看不到的內容留空，禁止創造資料。
7. 只能輸出 JSON object，不要 Markdown。
""".trimIndent()

    private fun obtainGpuEngine(context: Context, model: File): Engine {
        synchronized(lock) {
            val existing = engine
            if (existing != null && existing.isInitialized() && engineModelPath == model.absolutePath) return existing
            try { existing?.close() } catch (_: Throwable) {}
            engine = null
            engineModelPath = null

            Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
            val cache = File(context.cacheDir, "gemma_e4b_gpu_v211").apply { mkdirs() }
            val config = EngineConfig(
                modelPath = model.absolutePath,
                backend = Backend.GPU(),
                visionBackend = Backend.GPU(),
                maxNumImages = 1,
                cacheDir = cache.absolutePath
            )
            val created = Engine(config)
            created.initialize()
            engine = created
            engineModelPath = model.absolutePath
            return created
        }
    }

    private fun createCpuEngine(context: Context, model: File): Engine {
        val cache = File(context.cacheDir, "gemma_e4b_cpu_v211").apply { mkdirs() }
        val config = EngineConfig(
            modelPath = model.absolutePath,
            backend = Backend.CPU(threadCount = 4),
            visionBackend = Backend.CPU(threadCount = 4),
            maxNumImages = 1,
            cacheDir = cache.absolutePath
        )
        return Engine(config).also { it.initialize() }
    }

    private data class PreparedImages(val header: File, val table: File, val verify: File) {
        fun cleanup() {
            try { header.delete() } catch (_: Throwable) {}
            try { table.delete() } catch (_: Throwable) {}
            try { verify.delete() } catch (_: Throwable) {}
        }
    }

    private fun prepareImages(context: Context, imagePath: String): PreparedImages {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imagePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalArgumentException("無法解碼採購單圖片")

        var sample = 1
        while (bounds.outWidth / sample > 3400 || bounds.outHeight / sample > 4400) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bm = BitmapFactory.decodeFile(imagePath, opts) ?: throw IllegalArgumentException("無法載入採購單圖片")
        bm = applyExifOrientation(bm, imagePath)
        if (bm.width > bm.height) bm = rotateBitmap(bm, 90f)

        // 3200px is enough for the fixed A4 form after focused cropping and substantially reduces
        // image preprocessing/prefill versus repeatedly feeding 4000px crops.
        val maxSide = max(bm.width, bm.height)
        if (maxSide > 3200) {
            val scale = 3200f / maxSide.toFloat()
            val scaled = Bitmap.createScaledBitmap(
                bm,
                max(1, (bm.width * scale).toInt()),
                max(1, (bm.height * scale).toInt()),
                true
            )
            if (scaled !== bm) bm.recycle()
            bm = scaled
        }

        val dir = File(context.cacheDir, "gemma_doc_parts_v211").apply { mkdirs() }
        val tag = System.nanoTime().toString()
        val headerFile = File(dir, "header_$tag.jpg")
        val tableFile = File(dir, "table_$tag.jpg")
        val verifyFile = File(dir, "verify_$tag.jpg")

        val headerBottom = (bm.height * 0.32f).toInt().coerceAtMost(bm.height)
        val headerBm = Bitmap.createBitmap(bm, 0, 0, bm.width, max(1, headerBottom))
        saveJpegResized(headerBm, headerFile, 2100)
        headerBm.recycle()

        val tableTop = (bm.height * 0.13f).toInt().coerceAtLeast(0)
        val tableBottom = (bm.height * 0.72f).toInt().coerceAtMost(bm.height)
        val tableBm = Bitmap.createBitmap(bm, 0, tableTop, bm.width, max(1, tableBottom - tableTop))
        saveJpegResized(tableBm, tableFile, 2200)
        tableBm.recycle()

        val verifyBottom = (bm.height * 0.74f).toInt().coerceAtMost(bm.height)
        val verifyBm = Bitmap.createBitmap(bm, 0, 0, bm.width, max(1, verifyBottom))
        saveJpegResized(verifyBm, verifyFile, 2200)
        verifyBm.recycle()
        bm.recycle()

        return PreparedImages(headerFile, tableFile, verifyFile)
    }

    private fun applyExifOrientation(source: Bitmap, imagePath: String): Bitmap {
        val degrees = try {
            when (ExifInterface(imagePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (_: Throwable) { 0f }
        return if (degrees == 0f) source else rotateBitmap(source, degrees)
    }

    private fun rotateBitmap(source: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated !== source) source.recycle()
        return rotated
    }

    private fun saveJpegResized(source: Bitmap, file: File, maxWidth: Int) {
        var output = source
        if (source.width > maxWidth) {
            val scale = maxWidth.toFloat() / source.width.toFloat()
            output = Bitmap.createScaledBitmap(
                source,
                maxWidth,
                max(1, (source.height * scale).toInt()),
                true
            )
        }
        try {
            FileOutputStream(file).use { out ->
                if (!output.compress(Bitmap.CompressFormat.JPEG, 93, out)) {
                    throw IllegalStateException("影像轉換失敗")
                }
            }
        } finally {
            if (output !== source) output.recycle()
        }
    }

    private fun notifyProgress(cb: GemmaProgressCallback?, percent: Int, stage: String) {
        if (cb == null) return
        main.post { cb.onProgress(percent, stage) }
    }
}
