package com.quanyi.docscanner;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentSender;
import android.net.Uri;
import android.widget.Toast;

import com.google.mlkit.vision.documentscanner.GmsDocumentScanner;
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult;

import java.util.ArrayList;
import java.util.List;

final class AiDocumentScanner {
    private AiDocumentScanner() {}

    static void start(Activity activity, int requestCode, Runnable fallback) {
        try {
            GmsDocumentScannerOptions options = new GmsDocumentScannerOptions.Builder()
                    .setGalleryImportAllowed(true)
                    .setPageLimit(10)
                    .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                    .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                    .build();

            GmsDocumentScanner scanner = GmsDocumentScanning.getClient(options);
            scanner.getStartScanIntent(activity)
                    .addOnSuccessListener(intentSender -> {
                        try {
                            activity.startIntentSenderForResult(intentSender, requestCode, null, 0, 0, 0);
                        } catch (IntentSender.SendIntentException e) {
                            Toast.makeText(activity, "AI 掃描器無法啟動，已切換相容模式。", Toast.LENGTH_LONG).show();
                            if (fallback != null) fallback.run();
                        }
                    })
                    .addOnFailureListener(e -> {
                        Toast.makeText(activity, "此手機暫時無法使用 AI 掃描，已切換相容模式。", Toast.LENGTH_LONG).show();
                        if (fallback != null) fallback.run();
                    });
        } catch (Throwable e) {
            Toast.makeText(activity, "AI 掃描器初始化失敗，已切換相容模式。", Toast.LENGTH_LONG).show();
            if (fallback != null) fallback.run();
        }
    }

    static ArrayList<Uri> readPages(Intent data, int maxPages) {
        ArrayList<Uri> out = new ArrayList<>();
        if (data == null) return out;
        try {
            GmsDocumentScanningResult result = GmsDocumentScanningResult.fromActivityResultIntent(data);
            if (result == null) return out;
            List<GmsDocumentScanningResult.Page> pages = result.getPages();
            if (pages == null) return out;
            for (GmsDocumentScanningResult.Page page : pages) {
                if (page == null || out.size() >= maxPages) break;
                Uri uri = page.getImageUri();
                if (uri != null) out.add(uri);
            }
        } catch (Throwable ignored) {}
        return out;
    }
}
