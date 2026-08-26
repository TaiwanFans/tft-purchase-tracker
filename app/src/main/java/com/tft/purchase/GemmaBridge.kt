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

    /**
     * Gemma is the authoritative recognizer. The ocrText argument is intentionally ignored.
     * It is kept in the public signature only so older callers remain binary/source compatible.
     */
    @JvmStatic
    fun analyze(context: Context, imagePath: String, ocrText: String, callback: GemmaAiCallback) {
        val app = context.applicationContext
        Thread {
            try {
                val model = GemmaModelManager.modelFile(app)
                if (!GemmaModelManager.isReady(app)) throw IllegalStateException("Gemma AI 模型尚未安裝完成")
                val e = obtainEngine(app, model)
                val config = conversationConfig()
                val response = e.createConversation(config).use { conv ->
                    conv.sendMessage(
                        Contents.of(
                            Content.ImageFile(imagePath),
                            Content.Text(buildPrompt())
                        )
                    ).toString()
                }
                main.post { callback.onSuccess(response) }
            } catch (gpuError: Throwable) {
                // Retry once on CPU if the GPU path cannot initialize/infer.
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
                    val response = cpu.use { e2 ->
                        e2.createConversation(conversationConfig()).use { conv ->
                            conv.sendMessage(
                                Contents.of(
                                    Content.ImageFile(imagePath),
                                    Content.Text(buildPrompt())
                                )
                            ).toString()
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

    private fun conversationConfig(): ConversationConfig {
        return ConversationConfig(
            systemInstruction = Contents.of(
                "你是台灣製造業公司內部的採購單視覺辨識引擎。" +
                    "只根據目前這張採購單圖片判讀，不使用 OCR 猜測，不補不存在的字。" +
                    "你的工作不是抄下整張文件，而是辨識欄位與真正採購品項並輸出 JSON。" +
                    "看不清楚的欄位輸出空字串；寧可留空也不可猜錯。繁體中文。"
            ),
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 7),
            maxOutputToken = 2200
        )
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

    private fun buildPrompt(): String {
        return """
你正在看一張台灣公司的「採購單」圖片。請直接用視覺閱讀圖片，不要假設任何 OCR 文字正確，也不要把整張文件逐行抄寫。

【第一步：讀固定欄位】
只讀圖片中對應標籤旁的值：
- 供應廠商 → vendor
- 廠商地址 → location
- 採購單號 → order_no
- 採購日期 → purchase_date

採購單號通常在右上角「採購單號」右側，必須逐碼核對，不可少位數，不可把日期當單號。
採購日期統一輸出 YYYY-MM-DD。

【第二步：只讀正式品項表格】
真正的品項區域只在表頭「項次／品名規格明細／數量／單位／單價／小計／交貨日期／備註」下方，到「本頁小計」上方為止。
「本頁小計」以下的所有文字，一律不是品項，禁止放進 items。

每一列必須依欄位的水平位置分開閱讀，禁止把相鄰欄位數字混在一起：
1. line_no：最左側項次，例如 001。
2. description：品名規格明細，可把同一列內換行文字合併。
3. quantity：只取「數量」欄。必須完整讀數字，例如圖片是 200 就輸出 "200"，不可截成 "2"。
4. unit：只取「單位」欄。
5. unit_price：只取「單價」欄。
6. subtotal：只取「小計」欄。
7. delivery_date：只取同一列「交貨日期」欄，統一 YYYY-MM-DD。
8. note：只取同一列「備註」欄。

【什麼絕對不能建立成品項】
- 「以下空白」或意思相同的空白列。
- 數量為 0、只是用來表示表格結束的空白列。
- 本頁小計、稅額、總計、審核、主管、經辦人、廠商確認。
- 「出貨時請標柱／標註採購單編號」等出貨說明。
- 「本公司採購資料為公司機密」等機密聲明。
- 「收到本採購單後須於24小時內回傳」等注意事項。
- 「如內容文字及交期無法配合」等條款。
- 傳真章、FAXED、公司印章、簽名、頁尾說明。
- 表格外任何段落文字。

items 只保留真正要採購、具有實際品名的列。若一列只是「以下空白」，即使看到交貨日期也必須忽略。
同一個 line_no 不得重複建立。
不確定的欄位留空，不要用別欄數字補上。

輸出前請自己再檢查一次：
- order_no 是否完整。
- quantity 是否真的來自數量欄，而不是項次、單價或小計。
- items 是否全部位於「本頁小計」上方的正式表格內。
- 是否誤把頁尾 1、2、3、4、5、6 點說明當成品項；如果有，刪掉。

不要解釋、不要 Markdown、不要 ```，只能輸出一個 JSON object：
{
  "vendor":"",
  "location":"",
  "order_no":"",
  "purchase_date":"",
  "items":[
    {
      "line_no":"001",
      "description":"",
      "quantity":"",
      "unit":"",
      "unit_price":"",
      "subtotal":"",
      "delivery_date":"",
      "note":""
    }
  ]
}
""".trimIndent()
    }
}
