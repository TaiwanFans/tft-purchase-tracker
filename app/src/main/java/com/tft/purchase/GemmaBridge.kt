package com.tft.purchase

import android.content.Context
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

object GemmaBridge {
    private val lock = Any()
    @Volatile private var engine: Engine? = null
    @Volatile private var engineModelPath: String? = null
    private val main = Handler(Looper.getMainLooper())

    @JvmStatic
    fun isReady(context: Context): Boolean = GemmaModelManager.isReady(context)

    @JvmStatic
    fun analyze(context: Context, imagePath: String, ocrText: String, callback: GemmaAiCallback) {
        val app = context.applicationContext
        Thread {
            try {
                val model = GemmaModelManager.modelFile(app)
                if (!GemmaModelManager.isReady(app)) throw IllegalStateException("Gemma AI 模型尚未安裝完成")
                val e = obtainEngine(app, model)
                val system = Contents.of(
                    "你是台灣製造業公司內部的採購單資料擷取助手。你的唯一工作是閱讀採購單圖片，輸出忠實、可驗證的 JSON。" +
                    "不得猜測、不得補不存在的資料；看不清楚就輸出空字串。繁體中文。"
                )
                val config = ConversationConfig(
                    systemInstruction = system,
                    samplerConfig = SamplerConfig(topK = 8, topP = 0.9, temperature = 0.2, seed = 7),
                    maxOutputToken = 1800
                )
                val prompt = buildPrompt(ocrText)
                val response = e.createConversation(config).use { conv ->
                    conv.sendMessage(
                        Contents.of(
                            Content.ImageFile(imagePath),
                            Content.Text(prompt)
                        )
                    ).toString()
                }
                main.post { callback.onSuccess(response) }
            } catch (gpuError: Throwable) {
                // If a cached GPU engine failed, release it and retry once on CPU.
                try {
                    synchronized(lock) {
                        try { engine?.close() } catch (_: Throwable) {}
                        engine = null
                        engineModelPath = null
                    }
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
                    val config = ConversationConfig(
                        systemInstruction = Contents.of("只根據採購單圖片與 OCR 文字輸出 JSON，不確定就留空，不要猜。"),
                        samplerConfig = SamplerConfig(topK = 8, topP = 0.9, temperature = 0.2, seed = 7),
                        maxOutputToken = 1800
                    )
                    val response = cpu.use { e2 ->
                        e2.createConversation(config).use { conv ->
                            conv.sendMessage(Contents.of(Content.ImageFile(imagePath), Content.Text(buildPrompt(ocrText)))).toString()
                        }
                    }
                    main.post { callback.onSuccess(response) }
                } catch (cpuError: Throwable) {
                    val msg = "Gemma AI 辨識失敗：" + (cpuError.message ?: gpuError.message ?: cpuError.javaClass.simpleName)
                    main.post { callback.onFailure(msg) }
                }
            }
        }.start()
    }

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

    private fun buildPrompt(ocr: String): String {
        val clipped = if (ocr.length > 12000) ocr.substring(0, 12000) else ocr
        return """
請直接閱讀這張採購單圖片。這是全益台灣電扇公司常用的固定版型採購單。

你必須找出：
1. 供應廠商名稱
2. 廠商地址／所在地
3. 採購單號：通常在右上方『採購單號』標籤附近。必須逐字逐碼照圖片，不可自己編號。
4. 採購日期
5. 表格中每一個真正品項：項次、品名規格明細、數量、單位、單價、小計、交貨日期、備註。

重要規則：
- 每個品項可能有不同交貨日期，必須逐列擷取。
- 『以下空白』『本頁小計』『稅額』『總計』不是品項。
- OCR 可能把 0/O、1/I/l、5/S、8/B 看錯。以圖片本身為最高優先，OCR 文字只作輔助。
- 日期統一 YYYY-MM-DD。看不清楚就留空字串。
- 數量不要把單價或小計誤當數量。
- 不要解釋，不要 markdown，不要 ```，只輸出一個 JSON object。

JSON 格式必須完全如下：
{
  "vendor":"",
  "location":"",
  "order_no":"",
  "purchase_date":"",
  "items":[
    {"line_no":"001","description":"","quantity":"","unit":"","unit_price":"","subtotal":"","delivery_date":"","note":""}
  ]
}

以下是手機 OCR 的輔助文字，可能有錯字：
--- OCR START ---
$clipped
--- OCR END ---
""".trimIndent()
    }
}
