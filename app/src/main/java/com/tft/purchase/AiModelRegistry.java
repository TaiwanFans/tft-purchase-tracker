package com.tft.purchase;

import android.content.Context;
import android.content.SharedPreferences;

/** Central AI provider switch. v2.1.0 defaults to Firebase AI Logic / Gemini cloud vision. */
public final class AiModelRegistry {
    private static final String PREFS = "tft_settings";
    private static final String KEY_MODEL = "active_ai_model";
    public static final String GEMINI_FIREBASE = "firebase-gemini-3.5-flash";

    private static final AiModelProvider GEMINI = new GeminiFirebaseProvider();

    private AiModelRegistry() {}

    public static AiModelProvider active(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = p.getString(KEY_MODEL, GEMINI_FIREBASE);
        if (GEMINI_FIREBASE.equals(id)) return GEMINI;
        // Any retired local-model preference is migrated safely to Gemini cloud.
        p.edit().putString(KEY_MODEL, GEMINI_FIREBASE).apply();
        return GEMINI;
    }

    public static void setActive(Context context, String modelId) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_MODEL, modelId).apply();
    }
}
