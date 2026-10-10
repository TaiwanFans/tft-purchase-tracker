package com.tft.purchase;

import android.content.Context;
import android.content.SharedPreferences;

/** Central model switch. Future local models only need another AiModelProvider implementation. */
public final class AiModelRegistry {
    private static final String PREFS = "tft_settings";
    private static final String KEY_MODEL = "active_ai_model";
    public static final String MINICPM_V46 = "minicpm-v-4.6";

    private static final AiModelProvider MINICPM = new MiniCpmV46Provider();

    private AiModelRegistry() {}

    public static AiModelProvider active(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = p.getString(KEY_MODEL, MINICPM_V46);
        if (MINICPM_V46.equals(id)) return MINICPM;
        // Unknown/removed model IDs safely fall back to the shipping local provider.
        return MINICPM;
    }

    public static void setActive(Context context, String modelId) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_MODEL, modelId).apply();
    }
}
