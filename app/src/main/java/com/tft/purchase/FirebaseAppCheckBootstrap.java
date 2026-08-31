package com.tft.purchase;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.firebase.FirebaseApp;
import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory;

/**
 * Firebase App Check bootstrap for the privately distributed v2.1.x test APK.
 * Uses Firebase's official Debug Provider so sideloaded APKs can access AI Logic
 * while App Check is enforced. The generated debug secret stays in app-private
 * SharedPreferences and is only surfaced locally to the user for one-time
 * registration in Firebase Console.
 */
public final class FirebaseAppCheckBootstrap {
    private static volatile boolean installed;
    private static final String PREFS_TEMPLATE = "com.google.firebase.appcheck.debug.store.%s";
    private static final String DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET";

    private FirebaseAppCheckBootstrap() {}

    public static synchronized boolean ensure(Context context) {
        if (installed) return true;
        try {
            FirebaseApp app = FirebaseApp.initializeApp(context.getApplicationContext());
            if (app == null) app = FirebaseApp.getInstance();
            FirebaseAppCheck.getInstance(app).installAppCheckProviderFactory(
                    DebugAppCheckProviderFactory.getInstance());
            installed = true;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static String debugSecret(Context context) {
        try {
            FirebaseApp app = FirebaseApp.getInstance();
            String persistenceKey = app.getPersistenceKey();
            String prefsName = String.format(PREFS_TEMPLATE, persistenceKey);
            SharedPreferences prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
            String secret = prefs.getString(DEBUG_SECRET_KEY, null);
            return secret == null ? "" : secret.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    public static String consoleUrl() {
        try {
            FirebaseApp app = FirebaseApp.getInstance();
            String projectId = app.getOptions().getProjectId();
            String appId = app.getOptions().getApplicationId();
            if (projectId == null || projectId.isEmpty() || appId == null || appId.isEmpty()) return "";
            return "https://console.firebase.google.com/project/" + projectId
                    + "/appcheck/apps?selectedAppId=" + appId;
        } catch (Throwable t) {
            return "";
        }
    }
}
