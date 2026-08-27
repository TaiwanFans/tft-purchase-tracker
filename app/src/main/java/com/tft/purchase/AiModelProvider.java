package com.tft.purchase;

import android.content.Context;

/**
 * Replaceable local AI model contract.
 * The rest of the purchase app talks only to this interface, so MiniCPM-V can later
 * be swapped for Gemma/Qwen/another local vision model without changing database/UI logic.
 */
public interface AiModelProvider {
    String id();
    String displayName();
    boolean isReady(Context context);
    void analyze(Context context,
                 String imagePath,
                 String ocrText,
                 AiProgressCallback progress,
                 AiModelCallback callback);
}
