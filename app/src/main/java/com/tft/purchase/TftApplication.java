package com.tft.purchase;

import android.app.Application;
import android.content.Context;

public class TftApplication extends Application {
    private static Context appContext;

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();
    }

    public static Context appContext() {
        return appContext;
    }
}
