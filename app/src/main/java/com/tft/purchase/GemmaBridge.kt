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
 * On-device Gemma vision recognizer.
 * V2.0.7 uses three visual passes: header, item table, and full-page verification.
 * OCR is never used to fill fields.
 */
object GemmaBridge {
    private val lock = Any()
    @Volatile private var engine: Engine? = null
    @Volatile private var engineModelPath: String? = null
    private val main = Handler(Looper.getMainLooper())

    @JvmStatic
    fun isReady(context: Context): Boolean = GemmaModelManager.isReady(context)

    /** Legacy entry point. OCR text is deliberately ignored. */
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
                if (!GemmaModelManager.isReady(app)) throw IllegalStateException("Gemma AI 模型尚未安裝完成")
                notifyProgress(progress, 5, "準備採購單影像")
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
                    notifyProgress(progress, 8, "GPU 無法使用，改用 CPU AI")
                    val model = GemmaModelManager.modelFile(app)
                    val cpuConfig = EngineConfig(
                        modelPath = model.absolutePath,
                        backend = Backend.CPU(threadCount = 4),
                        visionBackend = Backend.CPU(threadCount = 4),
                        maxNumImages = 1,
                        cacheDir = File(app.cacheDir, "gemma_litert_cpu").apply { mkdirs() }.absolutePath
                    )
                    val cpu = Engine(cpuConfig)
                    cpu.initialize()
                    val response = cpu.use { runAdvanced(it, app, imagePath, progress) }
                    main.post { callback.onSuccess(response) }
                } catch (cpuError: Throwable) {
                    val msg = "Gemma AI 分析失敗：" + (cpuError.message ?: gpuError.message ?: cpuError.javaClass.simpleName)
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
            notifyProgress(progress, 15, "AI 讀取供應商、採購單號與採購日期")
            val header = send(
                e,
                prepared.header.absolutePath,
                headerPrompt(),
                "你是採購單表頭視覺辨識器。只讀圖片中看得到的標籤與值，禁止猜測。",
                700
            )

            notifyProgress(progress, 43, "AI 逐列讀取品項、數量與交貨日")
            val table = send(
                e,
                prepared.table.absolutePath,
                tablePrompt(),
                "你是採購單表格視覺辨識器。只讀正式表格列，頁尾與條款一律不是品項。",
                1800
            )

            notifyProgress(progress, 72, "AI 交叉檢查整張採購單")
            val candidate = "HEADER_CANDIDATE:\n${clip(header, 6000)}\n\nTABLE_CANDIDATE:\n${clip(table, 12000)}"
            val finalResponse = send(
                e,
                prepared.full.absolutePath,
                verifyPrompt(candidate),
                "你是採購單資料稽核器。圖片是唯一真實來源。候選資料只供核對；不符合圖片就修正或留空，絕不臆測。",
                2200
            )
            notifyProgress(progress, 91, "檢查欄位一致性")
            return finalResponse
        } finally {
            prepared.cleanup()
        }
    }

    private fun send(e: Engine, imagePath: String, prompt: String, system: String, maxTokens: Int): String {
        val config = ConversationConfig(
            systemInstruction = Contents.of(system),
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 17),
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

    private fun headerPrompt(): String = """
直接閱讀這張採購單上半部。只輸出 JSON，不要說明：
{
  "vendor":"",
  "location":"",
  "order_no":"",
  "purchase_date":""
}
規則：
- vendor 只取「供應廠商」右側內容，不要取本公司名稱。
- location 只取「廠商地址」右側內容。
- order_no 只取右上方「採購單號」的完整數字。此公司單號通常是 12 碼：YYYYMMDD + 4 碼流水號。
- purchase_date 只取「採購日期」，輸出 YYYY-MM-DD。
- 如果看不清楚就留空，禁止自己補字、改公司名或編造數字。
""".trimIndent()

    private fun tablePrompt(): String = """
直接閱讀採購單正式品項表格。只輸出 JSON，不要說明：
{
  "items":[
    {"line_no":"001","description":"","quantity":"","unit":"","unit_price":"","subtotal":"","delivery_date":"","note":""}
  ]
}
只能讀「項次／品名規格明細／數量／單位／單價／小計／交貨日期／備註」欄位下方，到「本頁小計」上方為止。

強制規則：
- line_no 必須是左欄實際看得到的 001、002…；沒有項次就不能建立品項。
- description 可合併同一列換行文字，但不得跨列。
- quantity 只能從「數量」欄逐位讀完整數字，例如 200 絕不能變成 2。
- unit、unit_price、subtotal、delivery_date、note 必須取同一列各自欄位。
- delivery_date 統一 YYYY-MM-DD。
- 「以下空白」、數量 0 的結束列、FAXED、印章、頁尾 1~6 點條款、本頁小計、稅額、總計全部排除。
- 不確定就留空，禁止用相鄰欄位猜補。
""".trimIndent()

    private fun verifyPrompt(candidate: String): String = """
你現在看到完整採購單。下面有前兩次視覺讀取的候選資料，但候選可能錯，圖片才是唯一真實來源。

$candidate

請重新逐欄核對圖片並輸出最終 JSON：
{
  "vendor":"",
  "location":"",
  "order_no":"",
  "purchase_date":"",
  "items":[
    {"line_no":"001","description":"","quantity":"","unit":"","unit_price":"","subtotal":"","delivery_date":"","note":""}
  ]
}

輸出前必須完成以下自我檢查：
1. order_no 必須來自右上角「採購單號」，不得把採購日期或電話當單號。若單號是 12 碼，前 8 碼應與採購日期 YYYYMMDD 相符；不相符就重新看圖片。
2. 每個 items 項次都必須真的出現在「本頁小計」上方表格最左欄。
3. quantity 必須取同列「數量」欄完整數字；不要截掉尾端 0。
4. 若 quantity、unit_price、subtotal 都看得清楚，請檢查 quantity × unit_price 是否等於 subtotal；不等就重新看該列。
5. delivery_date 只能取同列交貨日期欄，且通常不早於採購日期。
6. 「以下空白」與頁尾條款永遠不能放入 items。
7. 看不清楚的非必要金額欄可留空，但供應廠商、採購單號、採購日期、真正品項的品名、數量、交貨日期必須仔細重新辨識。
8. 不要補不存在的品項，不要把說明文字改寫成商品名稱。

只能輸出 JSON object，不要 Markdown、不要 ```、不要任何解釋。
""".trimIndent()

    private fun obtainEngine(context: Context, model: File): Engine {
        synchronized(lock) {
            val existing = engine
            if (existing != null && existing.isInitialized() && engineModelPath == model.absolutePath) return existing
            try { existing?.close() } catch (_: Throwable) {}
            Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
            val cache = File(context.cacheDir, "gemma_litert_gpu").apply { mkdirs() }
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

    private data class PreparedImages(val full: File, val header: File, val table: File) {
        fun cleanup() {
            try { full.delete() } catch (_: Throwable) {}
            try { header.delete() } catch (_: Throwable) {}
            try { table.delete() } catch (_: Throwable) {}
        }
    }

    private fun prepareImages(context: Context, imagePath: String): PreparedImages {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imagePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalArgumentException("無法解碼採購單圖片")
        var sample = 1
        while (bounds.outWidth / sample > 3000 || bounds.outHeight / sample > 3800) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bm = BitmapFactory.decodeFile(imagePath, opts) ?: throw IllegalArgumentException("無法載入採購單圖片")
        val maxSide = max(bm.width, bm.height)
        if (maxSide > 3000) {
            val scale = 3000f / maxSide.toFloat()
            val scaled = Bitmap.createScaledBitmap(bm, max(1, (bm.width * scale).toInt()), max(1, (bm.height * scale).toInt()), true)
            if (scaled !== bm) bm.recycle()
            bm = scaled
        }

        val dir = File(context.cacheDir, "gemma_doc_parts").apply { mkdirs() }
        val tag = System.nanoTime().toString()
        val fullFile = File(dir, "full_$tag.jpg")
        val headerFile = File(dir, "header_$tag.jpg")
        val tableFile = File(dir, "table_$tag.jpg")
        saveJpeg(bm, fullFile)

        val headerHeight = max(1, (bm.height * 0.34f).toInt())
        val headerBm = Bitmap.createBitmap(bm, 0, 0, bm.width, headerHeight)
        saveJpeg(headerBm, headerFile)
        headerBm.recycle()

        val tableTop = max(0, (bm.height * 0.15f).toInt())
        val tableBottom = max(tableTop + 1, (bm.height * 0.75f).toInt()).coerceAtMost(bm.height)
        val tableBm = Bitmap.createBitmap(bm, 0, tableTop, bm.width, tableBottom - tableTop)
        saveJpeg(tableBm, tableFile)
        tableBm.recycle()
        bm.recycle()
        return PreparedImages(fullFile, headerFile, tableFile)
    }

    private fun saveJpeg(bm: Bitmap, file: File) {
        FileOutputStream(file).use { out ->
            if (!bm.compress(Bitmap.CompressFormat.JPEG, 98, out)) throw IllegalStateException("影像轉換失敗")
        }
    }

    private fun notifyProgress(cb: GemmaProgressCallback?, percent: Int, stage: String) {
        if (cb == null) return
        main.post { cb.onProgress(percent, stage) }
    }

    private fun clip(s: String, max: Int): String = if (s.length <= max) s else s.substring(0, max)
}
