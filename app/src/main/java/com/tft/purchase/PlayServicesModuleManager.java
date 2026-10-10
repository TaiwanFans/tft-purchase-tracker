package com.tft.purchase;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.android.gms.common.moduleinstall.ModuleInstall;
import com.google.android.gms.common.moduleinstall.ModuleInstallClient;
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner;
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

/**
 * Requests Google Play services optional modules while the phone has internet.
 * PP-OCRv6 Medium remains bundled and always available offline; these modules are extra tools:
 * - Chinese ML Kit OCR for cross-checking
 * - Google document scanner for crop/rotation/cleaning UI
 *
 * Once Google Play services has installed the modules they can be used without network access.
 */
public final class PlayServicesModuleManager {
    private PlayServicesModuleManager() {}

    private static volatile boolean requested;
    private static TextRecognizer warmRecognizer;
    private static GmsDocumentScanner warmScanner;

    public static void prefetch(Context context) {
        if (context == null || requested) return;
        synchronized (PlayServicesModuleManager.class) {
            if (requested) return;
            requested = true;
        }

        final Context app = context.getApplicationContext();
        final SharedPreferences prefs = app.getSharedPreferences("tft_settings", Context.MODE_PRIVATE);
        try {
            warmRecognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            GmsDocumentScannerOptions options = new GmsDocumentScannerOptions.Builder()
                    .setGalleryImportAllowed(true)
                    .setPageLimit(20)
                    .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                    .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                    .build();
            warmScanner = GmsDocumentScanning.getClient(options);

            ModuleInstallClient client = ModuleInstall.getClient(app);
            client.areModulesAvailable(warmRecognizer, warmScanner)
                    .addOnSuccessListener(availability -> {
                        if (availability.areModulesAvailable()) {
                            prefs.edit()
                                    .putBoolean("play_vision_modules_ready", true)
                                    .putString("play_vision_modules_status", "Google OCR / 文件掃描模組已就緒")
                                    .apply();
                            return;
                        }
                        ModuleInstallRequest request = ModuleInstallRequest.newBuilder()
                                .addApi(warmRecognizer)
                                .addApi(warmScanner)
                                .build();
                        client.installModules(request)
                                .addOnSuccessListener(response -> prefs.edit()
                                        .putBoolean("play_vision_modules_requested", true)
                                        .putString("play_vision_modules_status", response.areModulesAlreadyInstalled()
                                                ? "Google OCR / 文件掃描模組已就緒"
                                                : "已要求 Google Play services 下載離線辨識模組")
                                        .apply())
                                .addOnFailureListener(e -> prefs.edit()
                                        .putString("play_vision_modules_status", "Google Play 模組下載稍後重試：" + safe(e))
                                        .apply());
                    })
                    .addOnFailureListener(e -> {
                        // Direct install is still worth trying when the availability query fails.
                        ModuleInstallRequest request = ModuleInstallRequest.newBuilder()
                                .addApi(warmRecognizer)
                                .addApi(warmScanner)
                                .build();
                        client.installModules(request)
                                .addOnSuccessListener(response -> prefs.edit()
                                        .putBoolean("play_vision_modules_requested", true)
                                        .putString("play_vision_modules_status", "已要求 Google Play services 下載離線辨識模組")
                                        .apply())
                                .addOnFailureListener(err -> prefs.edit()
                                        .putString("play_vision_modules_status", "Google Play 模組目前不可下載：" + safe(err))
                                        .apply());
                    });
        } catch (Throwable t) {
            prefs.edit().putString("play_vision_modules_status", "Google Play services 不可用：" + safe(t)).apply();
        }
    }

    public static String status(Context context) {
        if (context == null) return "";
        return context.getSharedPreferences("tft_settings", Context.MODE_PRIVATE)
                .getString("play_vision_modules_status", "首次有網路時會自動下載 Google 文件掃描與中文 OCR 模組");
    }

    private static String safe(Throwable t) {
        if (t == null) return "未知錯誤";
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }
}
