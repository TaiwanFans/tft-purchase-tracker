package com.tft.purchase;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import java.util.ArrayList;

/** Foreground service for the offline document/OCR/AI/validation pipeline. */
public class AiAnalysisService extends Service {
    public static final String ACTION_UPDATE = "com.tft.purchase.AI_JOB_UPDATE";
    private static final String CHANNEL = "local_ai_analysis_v216";
    private static final int NOTIFICATION_ID = 8235708;
    private volatile boolean processing = false;
    private int created = 0;
    private int warnings = 0;

    public static void start(Context context, ArrayList<String> imagePaths, long replaceId) {
        if (imagePaths == null || imagePaths.isEmpty()) return;
        AiJobStore.begin(context, imagePaths, replaceId);
        Intent i = new Intent(context, AiAnalysisService.class);
        i.putStringArrayListExtra("paths", imagePaths);
        i.putExtra("replace_id", replaceId);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i);
        else context.startService(i);
    }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, notification("準備文件校正與分區雙 OCR", 1, false));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (processing) return START_STICKY;
        ArrayList<String> paths = intent == null ? null : intent.getStringArrayListExtra("paths");
        long replaceId = intent == null ? -1 : intent.getLongExtra("replace_id", -1);
        AiJobStore.Snapshot snapshot = AiJobStore.get(this);
        if (paths == null || paths.isEmpty()) {
            if (snapshot.running() && snapshot.paths != null && !snapshot.paths.isEmpty()) {
                paths = new ArrayList<>(snapshot.paths);
                replaceId = snapshot.replaceId;
            }
        }
        if (paths == null || paths.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        AiModelProvider provider = AiModelRegistry.active(this);
        if (!provider.isReady(this)) {
            String msg = provider.displayName() + " 模型尚未下載、驗證並通過載入測試";
            AiJobStore.error(this, msg);
            publish(msg, 100, true);
            stopSelf();
            return START_NOT_STICKY;
        }

        processing = true;
        created = snapshot.running() ? snapshot.success : 0;
        warnings = snapshot.running() ? snapshot.warningCount : 0;
        int resumeIndex = snapshot.running() ? Math.max(0, Math.min(paths.size(), snapshot.nextIndex)) : 0;
        if (resumeIndex > 0) publish("背景服務已恢復，從第 " + (resumeIndex + 1) + " 張繼續", 2, false);
        processNext(paths, resumeIndex, replaceId);
        return START_STICKY;
    }

    private void processNext(ArrayList<String> paths, int index, long replaceId) {
        if (index >= paths.size()) {
            processing = false;
            AiJobStore.done(this, created, warnings);
            String msg;
            if (created <= 0) msg = "AI 分析結束，但沒有建立採購單";
            else if (warnings > 0) msg = "已建立 " + created + " 張採購單；" + warnings + " 張需要人工確認";
            else msg = "已建立 " + created + " 張採購單，點此查看";
            publish(msg, 100, true);
            sendUpdate();
            stopSelf();
            return;
        }

        final String originalPath = paths.get(index);
        final int total = paths.size();
        final int base = (int)((index * 100f) / total);
        final int span = Math.max(1, 100 / total);

        updateJob(base + Math.max(1, span * 4 / 100),
                "第 " + (index + 1) + "/" + total + " 張｜準備文件辨識",
                index, total);

        HybridOcrBridge.recognize(this, originalPath, (percent, stage) -> {
            int overall = Math.min(89, base + Math.max(1, Math.round(span * (percent / 100f))));
            String text = "第 " + (index + 1) + "/" + total + " 張｜" + stage;
            AiJobStore.progress(this, overall, text, index + 1, total, created, 0);
            publish(text, overall, false);
            sendUpdate();
        }, new HybridOcrBridge.Callback() {
            @Override public void onSuccess(HybridOcrBridge.Result result) {
                String modelImage = result.preparedImagePath == null || result.preparedImagePath.isEmpty()
                        ? originalPath : result.preparedImagePath;
                runLocalModel(paths, index, replaceId, originalPath, modelImage,
                        result.evidence, result, total, base, span);
            }

            @Override public void onFailure(String error) {
                // OCR failure still creates a retry record. MiniCPM may inspect the original image, but the
                // resulting record is always marked for confirmation and the photo is never discarded.
                String evidence = "[OCR_ERROR] " + (error == null ? "雙 OCR 未完成" : error);
                updateJob(base + Math.max(1, span * 72 / 100),
                        "第 " + (index + 1) + "/" + total + " 張｜OCR 未完成，保留原圖並嘗試本機 AI",
                        index, total);
                runLocalModel(paths, index, replaceId, originalPath, originalPath,
                        evidence, null, total, base, span);
            }
        });
    }

    private void runLocalModel(ArrayList<String> paths, int index, long replaceId,
                               String originalPath, String modelImagePath, String ocrEvidence,
                               HybridOcrBridge.Result hybridResult,
                               int total, int base, int span) {
        AiModelProvider provider = AiModelRegistry.active(this);
        provider.analyze(this, modelImagePath, ocrEvidence, (percent, stage) -> {
            int modelStart = 89;
            int modelSpan = 8;
            int mapped = modelStart + Math.max(0, Math.min(modelSpan, percent * modelSpan / 100));
            int overall = Math.min(97, base + Math.max(1, (int)(span * (mapped / 100f))));
            String text = "第 " + (index + 1) + "/" + total + " 張｜" + stage;
            AiJobStore.progress(this, overall, text, index + 1, total, created, 0);
            publish(text, overall, false);
            sendUpdate();
        }, new AiModelCallback() {
            @Override public void onSuccess(String response) {
                try {
                    updateJob(Math.min(98, base + Math.max(1, span * 97 / 100)),
                            "第 " + (index + 1) + "/" + total + " 張｜規則驗證：我方公司、日期、數量與金額",
                            index, total);
                    PurchaseOcrParser.ParsedPurchase parsed = AiJsonParser.parse(response, ocrEvidence);
                    AiResultValidator.Validation validation = AiResultValidator.validateAndSanitize(parsed);
                    boolean reliable = validation.reliable;
                    long id = saveParsed(parsed, originalPath,
                            replaceId > 0 && total == 1 ? replaceId : -1,
                            reliable, validation.message);
                    created++;
                    if (!reliable) warnings++;
                    AiJobStore.appendId(AiAnalysisService.this, id);
                    AiJobStore.checkpointNextIndex(AiAnalysisService.this, index + 1, created, warnings);
                    String stage = reliable
                            ? "第 " + (index + 1) + " 張已建立採購單 ✓"
                            : "第 " + (index + 1) + " 張已建立；衝突／不確定欄位需要人工確認";
                    AiJobStore.progress(AiAnalysisService.this, Math.min(99, base + span - 1),
                            stage, index + 1, total, created, 0);
                    publish(stage, Math.min(99, base + span - 1), false);
                    sendUpdate();
                } catch (Throwable t) {
                    try {
                        long id = savePlaceholder(originalPath,
                                replaceId > 0 && total == 1 ? replaceId : -1,
                                "AI 結果處理失敗：" + safeMessage(t), ocrEvidence);
                        created++;
                        warnings++;
                        AiJobStore.appendId(AiAnalysisService.this, id);
                        AiJobStore.checkpointNextIndex(AiAnalysisService.this, index + 1, created, warnings);
                    } catch (Throwable persistError) {
                        AiJobStore.error(AiAnalysisService.this, "無法保存 AI 失敗紀錄：" + safeMessage(persistError));
                    }
                } finally {
                    if (hybridResult != null) hybridResult.cleanup();
                }
                processNext(paths, index + 1, replaceId);
            }

            @Override public void onFailure(String error) {
                try {
                    long id = savePlaceholder(originalPath,
                            replaceId > 0 && total == 1 ? replaceId : -1,
                            error == null ? "本機 AI 分析失敗" : error, ocrEvidence);
                    created++;
                    warnings++;
                    AiJobStore.appendId(AiAnalysisService.this, id);
                    AiJobStore.checkpointNextIndex(AiAnalysisService.this, index + 1, created, warnings);
                    AiJobStore.progress(AiAnalysisService.this, Math.min(99, base + span - 1),
                            "第 " + (index + 1) + " 張已留下原圖＋OCR 原始資料，可重新 AI 分析",
                            index + 1, total, created, 0);
                    sendUpdate();
                } catch (Throwable persistError) {
                    AiJobStore.error(AiAnalysisService.this, "AI 失敗且無法建立重試紀錄：" + safeMessage(persistError));
                } finally {
                    if (hybridResult != null) hybridResult.cleanup();
                }
                processNext(paths, index + 1, replaceId);
            }
        });
    }

    private void updateJob(int progress, String text, int index, int total) {
        int p = Math.max(1, Math.min(99, progress));
        AiJobStore.progress(this, p, text, index + 1, total, created, 0);
        publish(text, p, false);
        sendUpdate();
    }

    private long savePlaceholder(String imagePath, long replaceId, String reason, String ocrEvidence) {
        PurchaseOcrParser.ParsedPurchase p = new PurchaseOcrParser.ParsedPurchase();
        p.rawText = "[AI_STATUS:RETRY]\n[AI_VALIDATION:" + safe(reason) + "]\n[OCR_RAW_EVIDENCE]\n" +
                safe(ocrEvidence) + "\n[/OCR_RAW_EVIDENCE]";
        return saveParsed(p, imagePath, replaceId, false, reason);
    }

    private long saveParsed(PurchaseOcrParser.ParsedPurchase parsed, String imagePath,
                            long replaceId, boolean reliable, String validationMessage) {
        PurchaseDbHelper db = new PurchaseDbHelper(this);
        PurchaseDbHelper.Purchase p;
        if (replaceId > 0) {
            p = db.get(replaceId);
            if (p == null) p = new PurchaseDbHelper.Purchase();
        } else {
            p = new PurchaseDbHelper.Purchase();
        }

        String preservedSignature = p.signatureReturnDate;
        String preservedNotes = p.notes;
        boolean preservedCompleted = p.completed;

        p.vendorName = parsed.vendor == null ? "" : parsed.vendor;
        // Absolute DB-boundary guard. This is deliberately duplicated after AI validation.
        if (AiResultValidator.isSelfCompany(p.vendorName)) {
            p.vendorName = "";
            reliable = false;
            validationMessage = "SELF_COMPANY：供應廠商誤抓到我方公司，已清空等待確認";
        }
        p.location = parsed.location == null ? "" : parsed.location;
        p.orderNo = parsed.orderNo == null ? "" : parsed.orderNo;
        p.purchaseDate = parsed.purchaseDate == null ? "" : parsed.purchaseDate;
        p.imagePath = imagePath == null ? "" : imagePath;
        p.rawOcr = (reliable ? "[AI_STATUS:OK]" : "[AI_STATUS:RETRY]") +
                "\n[AI_VALIDATION:" + (validationMessage == null ? "" : validationMessage) + "]\n" +
                (parsed.rawText == null ? "" : parsed.rawText);
        p.signatureReturnDate = preservedSignature == null ? "" : preservedSignature;
        p.notes = preservedNotes == null ? "" : preservedNotes;
        p.completed = preservedCompleted;

        StringBuilder legacy = new StringBuilder();
        String earliest = "";
        if (parsed.items != null) {
            for (PurchaseDbHelper.PurchaseItem i : parsed.items) {
                if (i == null) continue;
                if (legacy.length() > 0) legacy.append("\n");
                legacy.append(i.description == null ? "" : i.description);
                if (i.specification != null && !i.specification.isEmpty()) legacy.append("｜規格：").append(i.specification);
                if (i.quantity != null && !i.quantity.isEmpty()) {
                    legacy.append(" × ").append(i.quantity).append(i.unit == null ? "" : i.unit);
                }
                if (i.deliveryDate != null && !i.deliveryDate.isEmpty() &&
                        (earliest.isEmpty() || i.deliveryDate.compareTo(earliest) < 0)) {
                    earliest = i.deliveryDate;
                }
            }
        }
        p.legacyItems = legacy.toString();
        p.legacyDeliveryDate = earliest;

        long id;
        if (replaceId > 0 && p.id > 0) {
            db.update(p);
            id = p.id;
        } else {
            id = db.insert(p);
        }
        if (id <= 0) throw new IllegalStateException("SQLite 無法建立採購單紀錄");
        db.replaceItems(id, parsed.items == null ? new ArrayList<>() : parsed.items);
        try { DriveBackupHelper.backupIfDueAsync(this); } catch (Throwable ignored) {}
        return id;
    }

    private String safeMessage(Throwable t) {
        if (t == null) return "未知錯誤";
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel c = new NotificationChannel(CHANNEL, "本機採購單辨識", NotificationManager.IMPORTANCE_LOW);
        c.setDescription("文件校正、分區 PP-OCRv6、ML Kit、局部 MiniCPM 與規則驗證進度");
        c.setSound(null, null);
        nm.createNotificationChannel(c);
    }

    private Notification notification(String text, int progress, boolean finished) {
        Intent open = new Intent(this, AiMainActivity.class);
        open.putExtra("screen", finished ? "purchases" : "analysis");
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 71, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(finished ? "全益採購追蹤｜AI 結果已建立" : "全益採購追蹤｜分區 OCR + 局部 MiniCPM")
                .setContentText(text)
                .setContentIntent(pi)
                .setOnlyAlertOnce(true)
                .setOngoing(!finished)
                .setAutoCancel(finished)
                .setCategory(Notification.CATEGORY_PROGRESS);
        if (!finished) b.setProgress(100, Math.max(1, Math.min(99, progress)), false);
        return b.build();
    }

    private void publish(String text, int progress, boolean finished) {
        NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, notification(text, progress, finished));
    }

    private void sendUpdate() {
        Intent i = new Intent(ACTION_UPDATE);
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
