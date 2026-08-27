package com.tft.purchase;

import android.app.Application;
import android.content.Context;

public class TftApplication extends Application {
    private static Context appContext;

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();
        try {
            // Best-effort first-run download. PP-OCRv6 Medium is bundled, so OCR still has an
            // offline primary engine even if Google Play services is temporarily unavailable.
            PlayServicesModuleManager.prefetch(this);
        } catch (Throwable ignored) {}
    }

    public static Context appContext() {
        return appContext;
    }
}
