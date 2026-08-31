package com.tft.purchase;

import android.app.Application;
import android.content.Context;

import com.google.firebase.FirebaseApp;

public class TftApplication extends Application {
    private static Context appContext;

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();
        try { FirebaseApp.initializeApp(this); } catch (Throwable ignored) {}
        try {
            // PP-OCRv6 Small is bundled. Google document scanner/Chinese OCR are best-effort helpers.
            PlayServicesModuleManager.prefetch(this);
        } catch (Throwable ignored) {}
    }

    public static Context appContext() {
        return appContext;
    }
}
