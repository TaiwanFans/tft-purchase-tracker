package com.tft.purchase

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * V2.0.9 on-device Gemma 4 E4B document recognizer.
 * The fixed purchase-order layout is split into dedicated visual regions so tiny A4 text
 * is not crushed into one full-page vision input. OCR never writes fields directly.
 */
object GemmaBridge {
    private val lock = Any()
    @Volatile private var engine: Engine? = null
    @Volatile private var engineModelPath: String? = null
    private val main = Handler(Looper.getMainLooper())

    @JvmStatic
    fun isReady(context: Context): Boolean = GemmaModelManager.isReady(context)

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
            try {
                val model = GemmaModelManager.modelFile(app)
                if (!GemmaModelManager.isReady(app)) throw IllegalStateException("Gemma 4 E4B 模型尚未安裝完成")
                notifyProgress(progress, 4, "準備高解析採購單區域")
                val e = obtainEngine(app, model)
                val response = runAdvanced(e, app, imagePath, progress)
                main.post { callback.onSuccess(response) }
            } catch (gpuError: Throwable) {
                try {
                    synchronized(lock) {
                        try { engine?.close() } catch (_: Throwable) {}
                        engine = null
                        engineModelPath = null
                    }
                    notifyProgress(progress, 7, "GPU 無法使用，切換 CPU AI")
                    val model = GemmaModelManager.modelFile(app)
                    val cpuConfig = EngineConfig(
                        modelPath = model.absolutePath,
                        backend = Backend.CPU(threadCount = 4),
                        visionBackend = Backend.CPU(threadCount = 4),
                        maxNumImages = 1,
                        cacheDir = File(app.cacheDir, "gemma_e4b_cpu").apply { mkdirs() }.absolutePath
                    )
                    val cpu = Engine(cpuConfig)
                    cpu.initialize()
                    val response = cpu.use { runAdvanced(it, app, imagePath, progress) }
                    main.post { callback.onSuccess(response) }
                } catch (cpuError: Throwable) {
                    val msg = "Gemma 4 E4B 分析失敗：" + (cpuError.message ?: gpuError.message ?: cpuError.javaClass.simpleName)
                    main.post { callback.onFailure(msg) }
                }
            }
        }.start()
    }

    private fun runAdvanced(
        e: Engine,
        context: Context,
        imagePath: String,
        progress: GemmaProgressCallback?
    ): String {
        val prepared = prepareImages(context, imagePath)
        try {
            notifyProgress(progress, 15, "AI 放大辨識供應廠商與地址")
            val vendor = send(
                e,
                prepared.vendor.absolutePath,
                vendorPrompt(),
                "你是台灣採購單左上表頭辨識器。只抄圖片可見文字，不推測、不改寫。",
                650
            )

            notifyProgress(progress, 31, "AI 放大辨識採購單號與採購日期")
            val order = send(
                e,
                prepared.order.absolutePath,
                orderPrompt(),
                "你是台灣採購單右上編號日期辨識器。逐位抄寫數字；看不清就留空。",
                550
            )

            notifyProgress(progress, 49, "AI 放大逐列辨識品項與數量")
            val table = send(
                e,
                prepared.table.absolutePath,
                tablePrompt(),
                "你是固定格式採購單表格辨識器。只讀正式表格列；每個數字都要從欄位位置確認。",
                2000
            )

            notifyProgress(progress, 75, "AI 用上半頁交叉核對")
            val candidate = "VENDOR_CANDIDATE:\n${clip(vendor, 5000)}\n\nORDER_CANDIDATE:\n${clip(order, 4000)}\n\nTABLE_CANDIDATE:\n${clip(table, 13000)}"
            val finalResponse = send(
                e,
                prepared.verify.absolutePath,
                verifyPrompt(candidate),
                "你是採購單資料稽核器。圖片才是真實來源；候選資料只供核對。禁止補不存在的字或數字。",
                2400
            )
            notifyProgress(progress, 93, "程式檢查單號、日期與金額一致性")
            return finalResponse
        } finally {
            prepared.cleanup()
        }
    }

    private fun send(e: Engine, imagePath: String, prompt: String, system: String, maxTokens: Int): String {
        val config = ConversationConfig(
            systemInstruction = Contents.of(system),
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 29),
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

    private fun vendorPrompt(): String = """
這是採購單左上區域。只輸出 JSON：
{"vendor":"","location":""}
規則：
- vendor：只抄「供應廠商」標籤右側，到該行結束；括號內廠商代碼也保留。
- location：只抄「廠商地址」標籤右側，到該行結束。
- 不要抄最上方本公司名稱、電話或回送地址。
- 不要改正公司名稱，不要把看不清楚的中文字猜成常見公司名。
- 看不到就輸出空字串。
""".trimIndent()

    private fun orderPrompt(): String = """
這是採購單右上區域。只輸出 JSON：
{"order_no":"","purchase_date":""}
規則：
- order_no：只抄「採購單號」右側完整數字，逐位檢查。此版型通常為 YYYYMMDD + 4 碼流水號，共 12 碼。
- purchase_date：只抄「採購日期」右側日期，輸出 YYYY-MM-DD。
- 不得把聯絡人、電話、交貨日期當採購單號或採購日期。
- 若某位數字模糊就留空，不要猜 0/8、1/7、3/8。
""".trimIndent()

    private fun tablePrompt(): String = """
這是採購單正式品項表格的放大區域。只輸出 JSON：
{
  "items":[
    {"line_no":"001","description":"","quantity":"","unit":"","unit_price":"","subtotal":"","delivery_date":"","note":""}
  ]
}

只能讀表頭「項次／品名規格明細／數量／單位／單價／小計／交貨日期／備註」下方的正式資料列。
強制規則：
- 只有左欄真的出現 001、002、003… 才能建立品項。
- 「以下空白」不是品項；即使它有項次也必須排除。
- description 必須取同一列品名規格文字；同列換行可合併，不能跨列。
- quantity 必須從「數量」欄逐位完整抄寫，例如 200、1200 絕不能少尾端 0。
- unit、unit_price、subtotal、delivery_date、note 都必須取同一水平資料列。
- delivery_date 輸出 YYYY-MM-DD。
- 如果 quantity × unit_price 與 subtotal 明顯不相等，先重新看數量欄與單價欄，不要自行算一個新數字。
- FAXED、印章、頁尾條款、本頁小計、稅額、總計、公司機密文字全部不是品項。
- 看不清楚的欄位留空，不要用上下列內容猜補。
""".trimIndent()

    private fun verifyPrompt(candidate: String): String = """
這張圖片只保留採購單上半頁的重要區域。以下是三個放大區域得到的候選資料；候選可能有錯，請重新對照圖片。

$candidate

只輸出最終 JSON：
{
  "vendor":"",
  "location":"",
  "order_no":"",
  "purchase_date":"",
  "items":[
    {"line_no":"001","description":"","quantity":"","unit":"","unit_price":"","subtotal":"","delivery_date":"","note":""}
  ]
}

稽核規則：
1. vendor/location 只能來自左上「供應廠商／廠商地址」。
2. order_no 只能來自右上「採購單號」。若為 12 碼，前 8 碼通常應等於 purchase_date 的 YYYYMMDD；不一致時重新看右上區，不要硬改成合理數字。
3. purchase_date 只能來自右上「採購日期」，不是任何品項的交貨日期。
4. items 只接受正式表格列；「以下空白」與頁尾說明永遠排除。
5. quantity 要保留完整位數。若候選為 2，但圖片欄位看起來是 200，必須輸出 200。
6. 同列 quantity、unit_price、subtotal 都清楚時，用乘法檢查；若不合，重新辨識該列數字。
7. delivery_date 只能來自同列「交貨日期」欄。
8. 看不清楚就留空；不要創造公司名、品項、數字或日期。
9. 不要輸出 Markdown、說明文字或 ```，只能輸出 JSON object。
""".trimIndent()

    private fun obtainEngine(context: Context, model: File): Engine {
        synchronized(lock) {
            val existing = engine
            if (existing != null && existing.isInitialized() && engineModelPath == model.absolutePath) return existing
            try { existing?.close() } catch (_: Throwable) {}
            Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
            val cache = File(context.cacheDir, "gemma_e4b_gpu").apply { mkdirs() }
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

    private data class PreparedImages(val vendor: File, val order: File, val table: File, val verify: File) {
        fun cleanup() {
            try { vendor.delete() } catch (_: Throwable) {}
            try { order.delete() } catch (_: Throwable) {}
            try { table.delete() } catch (_: Throwable) {}
            try { verify.delete() } catch (_: Throwable) {}
        }
    }

    private fun prepareImages(context: Context, imagePath: String): PreparedImages {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imagePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalArgumentException("無法解碼採購單圖片")
        var sample = 1
        while (bounds.outWidth / sample > 3600 || bounds.outHeight / sample > 4600) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bm = BitmapFactory.decodeFile(imagePath, opts) ?: throw IllegalArgumentException("無法載入採購單圖片")
        val maxSide = max(bm.width, bm.height)
        if (maxSide > 3600) {
            val scale = 3600f / maxSide.toFloat()
            val scaled = Bitmap.createScaledBitmap(bm, max(1, (bm.width * scale).toInt()), max(1, (bm.height * scale).toInt()), true)
            if (scaled !== bm) bm.recycle()
            bm = scaled
        }

        val dir = File(context.cacheDir, "gemma_doc_parts_v209").apply { mkdirs() }
        val tag = System.nanoTime().toString()
        val vendorFile = File(dir, "vendor_$tag.jpg")
        val orderFile = File(dir, "order_$tag.jpg")
        val tableFile = File(dir, "table_$tag.jpg")
        val verifyFile = File(dir, "verify_$tag.jpg")

        // Left header: supplier and address. Crop aggressively so Chinese characters remain large.
        val vendorX = 0
        val vendorY = (bm.height * 0.045f).toInt().coerceAtLeast(0)
        val vendorW = (bm.width * 0.72f).toInt().coerceAtMost(bm.width)
        val vendorBottom = (bm.height * 0.285f).toInt().coerceAtMost(bm.height)
        val vendorBm = Bitmap.createBitmap(bm, vendorX, vendorY, vendorW, max(1, vendorBottom - vendorY))
        saveJpeg(vendorBm, vendorFile); vendorBm.recycle()

        // Right header: PO number and purchase date.
        val orderX = (bm.width * 0.53f).toInt().coerceAtLeast(0)
        val orderY = (bm.height * 0.045f).toInt().coerceAtLeast(0)
        val orderW = max(1, bm.width - orderX)
        val orderBottom = (bm.height * 0.285f).toInt().coerceAtMost(bm.height)
        val orderBm = Bitmap.createBitmap(bm, orderX, orderY, orderW, max(1, orderBottom - orderY))
        saveJpeg(orderBm, orderFile); orderBm.recycle()

        // Only the top populated portion of the table. Most of the lower A4 grid is blank and hurts vision resolution.
        val tableTop = (bm.height * 0.155f).toInt().coerceAtLeast(0)
        val tableBottom = (bm.height * 0.52f).toInt().coerceAtMost(bm.height)
        val tableBm = Bitmap.createBitmap(bm, 0, tableTop, bm.width, max(1, tableBottom - tableTop))
        saveJpeg(tableBm, tableFile); tableBm.recycle()

        // Final verification also excludes the page footer, stamps and terms.
        val verifyBottom = (bm.height * 0.56f).toInt().coerceAtMost(bm.height)
        val verifyBm = Bitmap.createBitmap(bm, 0, 0, bm.width, max(1, verifyBottom))
        saveJpeg(verifyBm, verifyFile); verifyBm.recycle()
        bm.recycle()

        return PreparedImages(vendorFile, orderFile, tableFile, verifyFile)
    }

    private fun saveJpeg(bm: Bitmap, file: File) {
        FileOutputStream(file).use { out ->
            if (!bm.compress(Bitmap.CompressFormat.JPEG, 100, out)) throw IllegalStateException("影像轉換失敗")
        }
    }

    private fun notifyProgress(cb: GemmaProgressCallback?, percent: Int, stage: String) {
        if (cb == null) return
        main.post { cb.onProgress(percent, stage) }
    }

    private fun clip(s: String, max: Int): String = if (s.length <= max) s else s.substring(0, max)
}
