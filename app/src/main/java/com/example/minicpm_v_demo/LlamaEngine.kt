package com.example.minicpm_v_demo

import android.content.Context
import java.io.File

/**
 * Minimal synchronous wrapper around OpenBMB's official Android llama.cpp JNI path.
 * It is intentionally isolated from the purchase app so the upper layer can swap models later.
 */
class LlamaEngine private constructor(private val appContext: Context) {
    companion object {
        @Volatile private var instance: LlamaEngine? = null

        @JvmStatic
        fun getInstance(context: Context): LlamaEngine = instance ?: synchronized(this) {
            instance ?: LlamaEngine(context.applicationContext).also { instance = it }
        }
    }

    private val lock = Any()
    @Volatile private var loadedModelPath: String? = null
    @Volatile private var loadedMmprojPath: String? = null
    private var usedOnce = false

    private external fun init(nativeLibDir: String)
    private external fun load(modelPath: String): Int
    private external fun loadMmproj(mmprojPath: String, imageMaxSliceNums: Int): Int
    private external fun setImageMaxSliceNumsNative(n: Int)
    private external fun setMinicpmvVersionNative(version: Int)
    private external fun getMinicpmvVersionNative(): Int
    private external fun prepare(): Int
    private external fun systemInfo(): String
    private external fun processSystemPrompt(systemPrompt: String): Int
    private external fun processUserPrompt(userPrompt: String, predictLength: Int): Int
    private external fun generateNextToken(): String?
    private external fun prefillImage(imageData: ByteArray, imageSize: Int): Int
    private external fun fullReset()
    private external fun nativeCancelGeneration()
    private external fun unload()
    private external fun shutdown()

    init {
        System.loadLibrary("minicpm_v_demo")
        init(appContext.applicationInfo.nativeLibraryDir)
    }

    fun ensureLoaded(modelPath: String, mmprojPath: String) = synchronized(lock) {
        if (loadedModelPath == modelPath && loadedMmprojPath == mmprojPath) return@synchronized
        require(File(modelPath).isFile) { "MiniCPM 主模型不存在" }
        require(File(mmprojPath).isFile) { "MiniCPM 視覺模型不存在" }

        if (loadedModelPath != null) {
            try { unload() } catch (_: Throwable) {}
            loadedModelPath = null
            loadedMmprojPath = null
        }

        val a = load(modelPath)
        if (a != 0) throw IllegalStateException("MiniCPM 主模型載入失敗，code=$a")

        // OCR supplies most of the tiny text. Two image slices preserve layout while keeping local inference usable.
        val b = loadMmproj(mmprojPath, 2)
        if (b != 0) {
            try { unload() } catch (_: Throwable) {}
            throw IllegalStateException("MiniCPM 視覺模型載入失敗，code=$b")
        }
        setMinicpmvVersionNative(46)
        val c = prepare()
        if (c != 0) {
            try { unload() } catch (_: Throwable) {}
            throw IllegalStateException("MiniCPM 推論環境初始化失敗，code=$c")
        }
        loadedModelPath = modelPath
        loadedMmprojPath = mmprojPath
        usedOnce = false
    }

    fun infer(imageBytes: ByteArray, prompt: String, predictLength: Int = 1200): String = synchronized(lock) {
        check(loadedModelPath != null) { "MiniCPM 模型尚未載入" }
        if (usedOnce) fullReset()

        val imageCode = prefillImage(imageBytes, imageBytes.size)
        if (imageCode != 0) throw IllegalStateException("MiniCPM 圖片預填失敗，code=$imageCode")

        val promptCode = processUserPrompt(prompt, predictLength)
        if (promptCode != 0) throw IllegalStateException("MiniCPM 提示詞處理失敗，code=$promptCode")

        val out = StringBuilder()
        while (true) {
            val token = generateNextToken() ?: break
            out.append(token)
            if (out.length > 24000) {
                nativeCancelGeneration()
                break
            }
        }
        usedOnce = true
        out.toString()
    }
}
